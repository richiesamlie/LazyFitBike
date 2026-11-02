package com.fitness.zwiftbike.wear

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pure Kotlin pedal cadence estimator from 3-axis gyroscope samples (rad/s).
 * Designed for Samsung Galaxy Watch 4 mounted on the ankle, shoe, or crank arm.
 *
 * Uses a hybrid fusion of:
 * 1. Mean angular velocity (accurate when strapped to crank arm or rotating smoothly)
 * 2. Dominant-axis oscillation cycle timing (accurate when worn on a human ankle that
 *    tilts back-and-forth once per pedal revolution rather than rotating a full 360 deg).
 */
class CadenceEstimator {
    private data class Sample(
        val omegaMag: Float,
        val dominantAxis: Float,
        val timestampNs: Long
    )

    private val samples = ArrayDeque<Sample>()
    private var smoothedRpm: Float = 0f
    private var lastSampleNs: Long = 0L

    companion object {
        private const val WINDOW_NS = 3_000_000_000L // 3.0 seconds rolling window
        private const val TIMEOUT_NS = 2_500_000_000L // 2.5 seconds inactivity timeout
        private const val DEADBAND_RAD_PER_SEC = 0.75f
        private const val MIN_ACTIVE_SAMPLES = 4
        private const val ANKLE_ARTICULATION_FACTOR = 0.85f
        private const val EMA_ALPHA = 0.25f
        private const val MIN_CYCLE_NS = 375_000_000L // 375ms -> max 160 RPM
        private const val MAX_CYCLE_NS = 1_700_000_000L // 1.7s -> min 35 RPM
    }

    @Synchronized
    fun onGyroSample(wx: Float, wy: Float, wz: Float, timestampNs: Long): Float {
        if (lastSampleNs > 0L && (timestampNs - lastSampleNs > TIMEOUT_NS || timestampNs < lastSampleNs)) {
            samples.clear()
            smoothedRpm = 0f
        }
        lastSampleNs = timestampNs

        val omega = sqrt(wx * wx + wy * wy + wz * wz)
        val dom = when {
            abs(wz) >= abs(wx) && abs(wz) >= abs(wy) -> wz
            abs(wx) >= abs(wy) -> wx
            else -> wy
        }
        samples.addLast(Sample(omega, dom, timestampNs))

        val cutoffNs = timestampNs - WINDOW_NS
        while (samples.isNotEmpty() && samples.first().timestampNs < cutoffNs) {
            samples.removeFirst()
        }

        if (samples.size < MIN_ACTIVE_SAMPLES) {
            return smoothedRpm
        }

        // Check recent 1.5s window for deadband gate so stopping feet drops RPM to 0
        val recentCutoffNs = timestampNs - 1_500_000_000L
        var recentSum = 0f
        var recentCount = 0
        var activeAboveDeadband = 0
        var totalSum = 0f
        for (s in samples) {
            totalSum += s.omegaMag
            if (s.timestampNs >= recentCutoffNs) {
                recentSum += s.omegaMag
                recentCount++
                if (s.omegaMag >= DEADBAND_RAD_PER_SEC) {
                    activeAboveDeadband++
                }
            }
        }

        val recentMeanOmega = if (recentCount > 0) recentSum / recentCount else 0f
        if (recentMeanOmega < DEADBAND_RAD_PER_SEC || activeAboveDeadband < 2) {
            smoothedRpm = 0f
            return 0f
        }

        val meanOmega = totalSum / samples.size
        val omegaRpm = ((meanOmega * 60.0) / (2.0 * PI * ANKLE_ARTICULATION_FACTOR))
            .toFloat()
            .coerceIn(0f, 160f)

        val cycleRpm = estimateCycleRpm()
        val rawRpm = if (cycleRpm > 35f) {
            max(omegaRpm, cycleRpm * 0.75f + omegaRpm * 0.25f).coerceIn(0f, 160f)
        } else {
            omegaRpm
        }

        smoothedRpm = if (smoothedRpm < 1f) {
            rawRpm
        } else {
            EMA_ALPHA * rawRpm + (1f - EMA_ALPHA) * smoothedRpm
        }
        return smoothedRpm
    }

    private fun estimateCycleRpm(): Float {
        if (samples.size < 8) return 0f
        val list = samples.toList()

        val crossingTimestamps = ArrayList<Long>()
        var armedNegative = false
        for (i in list.indices) {
            val v = list[i].dominantAxis
            if (v <= -0.25f) {
                armedNegative = true
            } else if (armedNegative && v >= 0.25f) {
                armedNegative = false
                val ts = list[i].timestampNs
                if (crossingTimestamps.isEmpty() || ts - crossingTimestamps.last() >= MIN_CYCLE_NS) {
                    crossingTimestamps.add(ts)
                }
            }
        }
        if (crossingTimestamps.size >= 2) {
            var intervalSumNs = 0L
            var validIntervals = 0
            for (i in 1 until crossingTimestamps.size) {
                val dtNs = crossingTimestamps[i] - crossingTimestamps[i - 1]
                if (dtNs in MIN_CYCLE_NS..MAX_CYCLE_NS) {
                    intervalSumNs += dtNs
                    validIntervals++
                }
            }
            if (validIntervals >= 1) {
                val meanPeriodSec = (intervalSumNs.toDouble() / validIntervals) / 1_000_000_000.0
                return (60.0 / meanPeriodSec).toFloat().coerceIn(35f, 160f)
            }
        }
        return 0f
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
