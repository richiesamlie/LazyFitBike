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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
    val maxHr: Int = 190,
    val hrStatus: String = "IDLE", // "IDLE", "NO PERM", "MEASURING", "OFF WRIST", or "LOCKED"
    val connectedNodes: Int = 0,
    val isWorkoutActive: Boolean = false,
    val targetWatts: Int = 0,
    val targetRpm: Int = 0,
    val stepRemainingSec: Int = 0,
    val workoutZone: Int = 1,
    val stepTitle: String = ""
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
    private lateinit var hrTrackerManager: HeartRateTrackerManager
    private val cadenceEstimator = CadenceEstimator()
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var latestHeartRateBpm: Int = 0
    private var simTick: Double = 0.0

    companion object {
        private const val CHANNEL_ID = "lazyfitbike_watch_workout"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.fitness.zwiftbike.wear.ACTION_START"
        const val ACTION_STOP = "com.fitness.zwiftbike.wear.ACTION_STOP"
        const val ACTION_TOGGLE_MODE = "com.fitness.zwiftbike.wear.ACTION_TOGGLE_MODE"
        const val ACTION_SET_MODE = "com.fitness.zwiftbike.wear.ACTION_SET_MODE"
        const val ACTION_SET_MAX_HR = "com.fitness.zwiftbike.wear.ACTION_SET_MAX_HR"
        const val ACTION_TOGGLE_SIM = "com.fitness.zwiftbike.wear.ACTION_TOGGLE_SIM"
        const val ACTION_REFRESH_SENSORS = "com.fitness.zwiftbike.wear.ACTION_REFRESH_SENSORS"
        const val ACTION_SET_WORKOUT_STEP = "com.fitness.zwiftbike.wear.ACTION_SET_WORKOUT_STEP"
        const val ACTION_CLEAR_WORKOUT = "com.fitness.zwiftbike.wear.ACTION_CLEAR_WORKOUT"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_MAX_HR = "extra_max_hr"
        const val EXTRA_WORKOUT_PAYLOAD = "extra_workout_payload"

        private val _state = MutableStateFlow(WatchWorkoutState())
        val state: StateFlow<WatchWorkoutState> = _state.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("lazyfitbike_watch_prefs", Context.MODE_PRIVATE).let {
            if (!it.contains("maxHr")) getSharedPreferences("velopulse_watch_prefs", Context.MODE_PRIVATE) else it
        }
        val savedMaxHr = prefs.getInt("maxHr", 190).coerceIn(130, 230)
        _state.value = _state.value.copy(maxHr = savedMaxHr)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        telemetrySender = TelemetrySender(this)
        hrTrackerManager = HeartRateTrackerManager(
            context = this,
            onBpmDetected = { bpm, _ ->
                latestHeartRateBpm = bpm
                _state.value = _state.value.copy(bpm = bpm, hrStatus = "LOCKED")
            },
            onStatusUpdate = { status ->
                if (latestHeartRateBpm == 0 || status == "NO PERM" || status == "OFF WRIST") {
                    _state.value = _state.value.copy(hrStatus = status)
                }
            }
        )

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
            ACTION_REFRESH_SENSORS -> {
                if (_state.value.isActive) {
                    registerSensors()
                }
            }
            ACTION_TOGGLE_MODE -> {
                val nextMode = if (_state.value.mode == "ANKLE") "WRIST" else "ANKLE"
                applyModeChange(nextMode)
            }
            ACTION_SET_MODE -> {
                val targetMode = intent.getStringExtra(EXTRA_MODE)?.uppercase() ?: "ANKLE"
                if (targetMode == "ANKLE" || targetMode == "WRIST") {
                    applyModeChange(targetMode)
                }
            }
            ACTION_SET_MAX_HR -> {
                val nextMaxHr = (intent.getIntExtra(EXTRA_MAX_HR, 190)).coerceIn(130, 230)
                getSharedPreferences("lazyfitbike_watch_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putInt("maxHr", nextMaxHr)
                    .apply()
                _state.value = _state.value.copy(maxHr = nextMaxHr)
            }
            ACTION_TOGGLE_SIM -> {
                val nextSim = !_state.value.isSimulated
                _state.value = _state.value.copy(isSimulated = nextSim)
            }
            ACTION_SET_WORKOUT_STEP -> {
                val payload = intent.getStringExtra(EXTRA_WORKOUT_PAYLOAD) ?: ""
                handleWorkoutStep(payload)
            }
            ACTION_CLEAR_WORKOUT -> {
                _state.value = _state.value.copy(
                    isWorkoutActive = false,
                    targetWatts = 0,
                    targetRpm = 0,
                    stepRemainingSec = 0,
                    workoutZone = 1,
                    stepTitle = ""
                )
            }
        }
        return START_STICKY
    }

    private fun handleWorkoutStep(payload: String) {
        val parts = payload.split("|")
        if (parts.size < 4) return
        val watts = parts[0].toIntOrNull() ?: 0
        val rpm = parts[1].toIntOrNull() ?: 0
        val remainingSec = parts[2].toIntOrNull() ?: 0
        val zone = parts[3].toIntOrNull() ?: 1
        val title = if (parts.size >= 5) parts[4] else ""

        _state.value = _state.value.copy(
            isWorkoutActive = true,
            targetWatts = watts,
            targetRpm = rpm,
            stepRemainingSec = remainingSec,
            workoutZone = zone,
            stepTitle = title
        )

        // Haptic transition cue on 3 seconds remaining
        if (remainingSec == 3) {
            triggerTransitionHaptic()
        }
    }

    private fun triggerTransitionHaptic() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                val pattern = longArrayOf(0, 150, 100, 150)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }
        }
    }

    private fun applyModeChange(nextMode: String) {
        cadenceEstimator.reset()
        _state.value = _state.value.copy(
            mode = nextMode,
            rpm = if (nextMode == "WRIST" && !_state.value.isSimulated) 0f else _state.value.rpm
        )
        // Immediately broadcast mode sync packet over Bluetooth & UDP even when workout is not active
        serviceScope.launch {
            telemetrySender.sendTelemetry(
                mode = nextMode,
                rpm = _state.value.rpm,
                bpm = _state.value.bpm,
                timestampMs = System.currentTimeMillis()
            )
        }
    }

    private fun startWorkout() {
        if (_state.value.isActive) {
            registerSensors()
            return
        }
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LazyFitBike Sensor Active")
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
                    // Only compute gyroscope RPM in ANKLE mode; in WRIST mode keep RPM at 0f
                    liveRpm = if (current.mode == "ANKLE") {
                        cadenceEstimator.getCurrentRpm(nowNs)
                    } else {
                        0f
                    }
                    liveBpm = latestHeartRateBpm
                }

                val nextHrStatus = when {
                    current.isSimulated -> "SIM"
                    !HeartRateTrackerManager.hasHeartRatePermission(this@SensorWorkoutService) -> "NO PERM"
                    liveBpm > 0 -> "LOCKED"
                    else -> if (current.hrStatus == "OFF WRIST") "OFF WRIST" else "MEASURING"
                }

                _state.value = current.copy(rpm = liveRpm, bpm = liveBpm, hrStatus = nextHrStatus)
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
        _state.value = _state.value.copy(isActive = false, rpm = 0f, hrStatus = "IDLE")
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun registerSensors() {
        sensorManager.unregisterListener(this)
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
        hrTrackerManager.start()
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
        hrTrackerManager.stop()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val nowRealtimeNs = SystemClock.elapsedRealtimeNanos()
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                if (_state.value.mode == "ANKLE") {
                    val wx = event.values.getOrElse(0) { 0f }
                    val wy = event.values.getOrElse(1) { 0f }
                    val wz = event.values.getOrElse(2) { 0f }
                    cadenceEstimator.onGyroSample(wx, wy, wz, nowRealtimeNs)
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                if (_state.value.mode == "ANKLE") {
                    val ax = event.values.getOrElse(0) { 0f }
                    val ay = event.values.getOrElse(1) { 0f }
                    val az = event.values.getOrElse(2) { 0f }
                    val dynMag = kotlin.math.abs(kotlin.math.sqrt(ax * ax + ay * ay + az * az) - 9.81f)
                    cadenceEstimator.onGyroSample(dynMag * 0.45f, 0f, 0f, nowRealtimeNs)
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
            "LazyFitBike::WatchSensorWakeLock"
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
            "LazyFitBike Watch Sensor",
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
