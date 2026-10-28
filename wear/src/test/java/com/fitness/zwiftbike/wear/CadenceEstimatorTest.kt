package com.fitness.zwiftbike.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class CadenceEstimatorTest {

    @Test
    fun stationaryGyroSamplesReturnZeroRpm() {
        val estimator = CadenceEstimator()
        var ts = 1_000_000_000L
        repeat(20) {
            val rpm = estimator.onGyroSample(0.05f, -0.04f, 0.02f, ts)
            assertEquals(0f, rpm, 0.01f)
            ts += 50_000_000L // 50ms
        }
    }

    @Test
    fun activePedalingGyroProducesExpectedCadenceAndTimesOutWhenStopped() {
        val estimator = CadenceEstimator()
        val targetRpm = 85f
        // omega = (2 * PI * targetRpm * 0.85) / 60
        val expectedOmega = ((2.0 * PI * targetRpm * 0.85) / 60.0).toFloat()

        var ts = 1_000_000_000L
        var lastRpm = 0f
        repeat(40) {
            lastRpm = estimator.onGyroSample(0f, 0f, expectedOmega, ts)
            ts += 50_000_000L // 20 Hz
        }

        assertTrue("Expected cadence near 85 RPM, got $lastRpm", lastRpm in 80f..90f)

        // Advance time by 3.0 seconds without pedaling -> should drop to 0 RPM
        val afterStopRpm = estimator.getCurrentRpm(ts + 3_000_000_000L)
        assertEquals(0f, afterStopRpm, 0.01f)
    }
}
