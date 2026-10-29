package com.fitness.zwiftbike.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math. roundToInt
import kotlin.math.sin

data class WatchWorkoutState(
    val isActive: Boolean = false,
    val mode: String = "ANKLE", // "ANKLE" or "WRIST"
    val isSimulated: Boolean = false,
    val rpm: Float = 0f,
    val bpm: Int = 0,
    val connectedNodes: Int = 0
)

/**
 * Foreground workout service keeping Galaxy Watch 4 sensors active at 10 Hz
 * even when the watch screen dims during an indoor cycling session.
 */
class SensorWorkoutService : Service(), SensorEventListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var transmitJob: Job? = null

    private lateinit var sensorManager: SensorManager
    private lateinit var telemetrySender: TelemetrySender
    private val cadenceEstimator = CadenceEstimator()
    private var wakeLock: PowerManager.WakeLock? = null

    private var latestHeartRateBpm: Int = 0
    private var simTick: Double = 0.0

    companion object {
        private const val CHANNEL_ID = "velopulse_watch_workout"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.fitness.zwiftbike.wear.ACTION_START"
        const val ACTION_STOP = "com.fitness.zwiftbike.wear.ACTION_STOP"
        const val ACTION_TOGGLE_MODE = "com.fitness.zwiftbike.wear.ACTION_TOGGLE_MODE"
        const val ACTION_TOGGLE_SIM = "com.fitness.zwiftbike.wear.ACTION_TOGGLE_SIM"

        private val _state = MutableStateFlow(WatchWorkoutState())
        val state: StateFlow<WatchWorkoutState> = _state.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        telemetrySender = TelemetrySender(this)

        serviceScope.launch {
            telemetrySender.connectedNodeCount.collect { count ->
                _state.value = _state.value.copy(connectedNodes = count)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startWorkout()
            ACTION_STOP -> stopWorkout()
            ACTION_TOGGLE_MODE -> {
                val nextMode = if (_state.value.mode == "ANKLE") "WRIST" else "ANKLE"
                cadenceEstimator.reset()
                _state.value = _state.value.copy(mode = nextMode)
            }
            ACTION_TOGGLE_SIM -> {
                val nextSim = !_state.value.isSimulated
                _state.value = _state.value.copy(isSimulated = nextSim)
            }
        }
        return START_STICKY
    }

    private fun startWorkout() {
        if (_state.value.isActive) return
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VeloPulse Sensor Active")
            .setContentText("Streaming cadence and heart rate to phone")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }

        acquireWakeLock()
        registerSensors()
        _state.value = _state.value.copy(isActive = true)

        transmitJob?.cancel()
        transmitJob = serviceScope.launch {
            while (isActive && _state.value.isActive) {
                val current = _state.value
                val nowNs = SystemClock.elapsedRealtimeNanos()
                val nowMs = System.currentTimeMillis()

                val liveRpm: Float
                val liveBpm: Int

                if (current.isSimulated) {
                    simTick += 0.1
                    liveRpm = (86.0 + 6.0 * sin(simTick * 0.5)).toFloat()
                    liveBpm = (144.0 + 5.0 * sin(simTick * 0.25)).roundToInt()
                } else {
                    liveRpm = cadenceEstimator.getCurrentRpm(nowNs)
                    liveBpm = latestHeartRateBpm
                }

                _state.value = current.copy(rpm = liveRpm, bpm = liveBpm)
                telemetrySender.sendTelemetry(
                    mode = current.mode,
                    rpm = liveRpm,
                    bpm = liveBpm,
                    timestampMs = nowMs
                )
                delay(100L) // 10 Hz stream
            }
        }
    }

    private fun stopWorkout() {
        transmitJob?.cancel()
        unregisterSensors()
        releaseWakeLock()
        cadenceEstimator.reset()
        _state.value = _state.value.copy(isActive = false, rpm = 0f)
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun registerSensors() {
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyro != null) {
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        } else {
            val accel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null) {
                sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        val heartRate = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (heartRate != null) {
            sensorManager.registerListener(this, heartRate, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val nowRealtimeNs = SystemClock.elapsedRealtimeNanos()
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val wx = event.values.getOrElse(0) { 0f }
                val wy = event.values.getOrElse(1) { 0f }
                val wz = event.values.getOrElse(2) { 0f }
                // Use SystemClock.elapsedRealtimeNanos() so onGyroSample and getCurrentRpm share the same clock domain
                cadenceEstimator.onGyroSample(wx, wy, wz, nowRealtimeNs)
            }
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values.getOrElse(0) { 0f }
                val ay = event.values.getOrElse(1) { 0f }
                val az = event.values.getOrElse(2) { 0f }
                val dynMag = kotlin.math.abs(kotlin.math.sqrt(ax * ax + ay * ay + az * az) - 9.81f)
                cadenceEstimator.onGyroSample(dynMag * 0.45f, 0f, 0f, nowRealtimeNs)
            }
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values.getOrElse(0) { 0f }.roundToInt()
                if (bpm in 35..225) {
                    latestHeartRateBpm = bpm
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VeloPulse::WatchSensorWakeLock"
        ).apply {
            acquire(2 * 60 * 60 * 1000L) // 2 hours max safety timeout
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VeloPulse Watch Sensor",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopWorkout()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
