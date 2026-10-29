package com.fitness.zwiftbike.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlin.math.roundToInt

/**
 * Multi-source Heart Rate engine modelled on Selin2005/Heartline:
 * 1. Samsung Health Sensor SDK 1.4.1 (HEART_RATE_CONTINUOUS + IBI median fallback)
 * 2. Wear OS Health Services MeasureClient (DataType.HEART_RATE_BPM)
 * 3. Android SensorManager (all wake-up & non-wake-up TYPE_HEART_RATE sensors)
 */
class HeartRateTrackerManager(
    private val context: Context,
    private val onBpmDetected: (bpm: Int, source: String) -> Unit,
    private val onStatusUpdate: (status: String) -> Unit
) : SensorEventListener {

    companion object {
        const val PERMISSION_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        const val PERMISSION_READ_HEALTH_BG = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        const val PERMISSION_SAMSUNG_ADDITIONAL =
            "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"

        /**
         * Returns true if the watch has the required runtime HR permission for its Android API level
         * (API 36+ uses android.permission.health.READ_HEART_RATE; API <= 35 uses BODY_SENSORS).
         */
        fun hasHeartRatePermission(context: Context): Boolean {
            val hasBodySensors = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BODY_SENSORS
            ) == PackageManager.PERMISSION_GRANTED
            val hasGranularHr = if (Build.VERSION.SDK_INT >= 36) {
                ContextCompat.checkSelfPermission(
                    context,
                    PERMISSION_READ_HEART_RATE
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
            return hasBodySensors || hasGranularHr
        }

        /**
         * Permissions to request at runtime in MainActivity (following Heartline's PermissionPolicy).
         */
        fun requiredRuntimePermissions(): List<String> {
            val list = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 36) {
                list.add(PERMISSION_READ_HEART_RATE)
            } else {
                list.add(Manifest.permission.BODY_SENSORS)
            }
            list.add(Manifest.permission.BODY_SENSORS)
            list.add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return list.distinct()
        }
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var samsungService: HealthTrackingService? = null
    private var samsungHrTracker: HealthTracker? = null
    private var isMeasureCallbackRegistered = false
    private var isRunning = false

    private val measureCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(
            dataType: DeltaDataType<*, *>,
            availability: Availability
        ) = Unit

        override fun onDataReceived(data: DataPointContainer) {
            val points = data.getData(DataType.HEART_RATE_BPM)
            for (pt in points) {
                val bpm = pt.value.roundToInt()
                if (bpm in 30..230) {
                    onBpmDetected(bpm, "HS")
                }
            }
        }
    }

    private val samsungConnectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            if (!isRunning) return
            runCatching {
                val svc = samsungService ?: return
                val supported = svc.trackingCapability?.supportHealthTrackerTypes.orEmpty()
                if (HealthTrackerType.HEART_RATE_CONTINUOUS in supported) {
                    val tracker = svc.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
                    samsungHrTracker = tracker
                    tracker.setEventListener(object : HealthTracker.TrackerEventListener {
                        override fun onDataReceived(points: List<DataPoint>) {
                            for (point in points) {
                                val rawHr = runCatching {
                                    point.getValue(ValueKey.HeartRateSet.HEART_RATE)
                                }.getOrNull() ?: 0
                                val status = runCatching {
                                    point.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
                                }.getOrNull() ?: 0
                                val ibis = runCatching {
                                    point.getValue(ValueKey.HeartRateSet.IBI_LIST)
                                }.getOrNull().orEmpty()

                                val resolvedBpm = when {
                                    rawHr in 30..230 -> rawHr
                                    // Heartline HrSamples fallback: compute BPM from IBI_LIST during initial lock
                                    ibis.isNotEmpty() -> {
                                        val validIbis = ibis.filter { it in 260..2000 }.sorted()
                                        if (validIbis.isNotEmpty()) {
                                            val medianIbi = validIbis[validIbis.size / 2]
                                            (60_000 / medianIbi).coerceIn(30, 230)
                                        } else 0
                                    }
                                    else -> 0
                                }

                                if (resolvedBpm in 30..230) {
                                    onBpmDetected(resolvedBpm, "SDK")
                                } else if (status == -3) {
                                    onStatusUpdate("OFF WRIST")
                                }
                            }
                        }

                        override fun onFlushCompleted() = Unit

                        override fun onError(error: HealthTracker.TrackerError) {
                            // If SDK_POLICY_ERROR (Developer Mode off), MeasureClient & SensorManager still run
                        }
                    })
                }
            }
        }

        override fun onConnectionEnded() = Unit

        override fun onConnectionFailed(e: HealthTrackerException) = Unit
    }

    fun start() {
        isRunning = true
        if (!hasHeartRatePermission(context)) {
            onStatusUpdate("NO PERM")
            return
        }
        onStatusUpdate("MEASURING")
        startSensorManagerHr()
        startHealthServicesMeasureClient()
        startSamsungSdkTracker()
    }

    fun stop() {
        isRunning = false
        runCatching { sensorManager.unregisterListener(this) }
        if (isMeasureCallbackRegistered) {
            isMeasureCallbackRegistered = false
            runCatching {
                HealthServices.getClient(context)
                    .measureClient
                    .unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, measureCallback)
            }
        }
        runCatching { samsungHrTracker?.unsetEventListener() }
        samsungHrTracker = null
        runCatching { samsungService?.disconnectService() }
        samsungService = null
    }

    private fun startSensorManagerHr() {
        runCatching { sensorManager.unregisterListener(this) }
        val hrSensors = mutableListOf<Sensor>()
        hrSensors.addAll(sensorManager.getSensorList(Sensor.TYPE_HEART_RATE))
        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE, true)?.let {
            if (it !in hrSensors) hrSensors.add(it)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE, false)?.let {
            if (it !in hrSensors) hrSensors.add(it)
        }
        for (sensor in hrSensors) {
            val registered = sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            if (!registered) {
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    private fun startHealthServicesMeasureClient() {
        runCatching {
            val measureClient = HealthServices.getClient(context).measureClient
            measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, measureCallback)
            isMeasureCallbackRegistered = true
        }
    }

    private fun startSamsungSdkTracker() {
        runCatching {
            samsungService?.disconnectService()
            samsungService = HealthTrackingService(
                samsungConnectionListener,
                context.applicationContext
            ).also { it.connectService() }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type == Sensor.TYPE_HEART_RATE) {
            val raw = event.values.getOrElse(0) { 0f }
            if (!raw.isNaN() && raw >= 30f) {
                val bpm = raw.roundToInt().coerceIn(30, 230)
                onBpmDetected(bpm, "SEN")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
