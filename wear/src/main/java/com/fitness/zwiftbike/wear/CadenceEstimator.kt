package com.fitness.zwiftbike.wear

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Pure Kotlin pedal cadence estimator from 3-axis gyroscope samples (rad/s).
 * Designed for Samsung Galaxy Watch 4 mounted on the ankle, shoe, or crank arm.
 */
class CadenceEstimator {
    private data class Sample(val omegaMag: Float, val timestampNs: Long)

    private val samples = ArrayDeque<Sample>()
    private var smoothedRpm: Float = 0f
    private var lastSampleNs: Long = 0L

    companion object {
        private const val WINDOW_NS = 3_000_000_000L // 3.0 seconds rolling window
        private const val TIMEOUT_NS = 2_500_000_000L // 2.5 seconds inactivity timeout
        private const val DEADBAND_RAD_PER_SEC = 0.45f
        private const val ANKLE_ARTICULATION_FACTOR = 0.85f
        private const val EMA_ALPHA = 0.25f
    }

    @Synchronized
    fun onGyroSample(wx: Float, wy: Float, wz: Float, timestampNs: Long): Float {
        if (lastSampleNs > 0L && (timestampNs - lastSampleNs > TIMEOUT_NS || timestampNs < lastSampleNs)) {
            samples.clear()
            smoothedRpm = 0f
        }
        lastSampleNs = timestampNs

        val omega = sqrt(wx * wx + wy * wy + wz * wz)
        samples.addLast(Sample(omega, timestampNs))

        val cutoffNs = timestampNs - WINDOW_NS
        while (samples.isNotEmpty() && samples.first().timestampNs < cutoffNs) {
            samples.removeFirst()
        }

        if (samples.size < 4) {
            return smoothedRpm
        }

        // Check recent 1.5s window for deadband gate so stopping feet drops RPM to 0
        val recentCutoffNs = timestampNs - 1_500_000_000L
        var recentSum = 0f
        var recentCount = 0
        var totalSum = 0f
        for (s in samples) {
            totalSum += s.omegaMag
            if (s.timestampNs >= recentCutoffNs) {
                recentSum += s.omegaMag
                recentCount++
            }
        }

        val recentMeanOmega = if (recentCount > 0) recentSum / recentCount else 0f
        if (recentMeanOmega < DEADBAND_RAD_PER_SEC) {
            smoothedRpm = 0f
            return 0f
        }

        val meanOmega = totalSum / samples.size
        val rawRpm = ((meanOmega * 60.0) / (2.0 * PI * ANKLE_ARTICULATION_FACTOR))
            .toFloat()
            .coerceIn(0f, 160f)

        smoothedRpm = if (smoothedRpm < 1f) {
            rawRpm
        } else {
            EMA_ALPHA * rawRpm + (1f - EMA_ALPHA) * smoothedRpm
        }
        return smoothedRpm
    }

    @Synchronized
    fun getCurrentRpm(nowNs: Long): Float {
        if (lastSampleNs == 0L || nowNs - lastSampleNs > TIMEOUT_NS) {
            smoothedRpm = 0f
            samples.clear()
            return 0f
        }
        return smoothedRpm
    }

    @Synchronized
    fun reset() {
        samples.clear()
        smoothedRpm = 0f
        lastSampleNs = 0L
    }
}
