package com.fitness.zwiftbike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CyclingPhysicsTest {

    @Test
    fun ankleModeHigherGearProducesMorePower() {
        val physics = CyclingPhysics()
        val (wattsLowGear, _) = physics.computePowerWatts(
            mode = "ANKLE",
            rpm = 85f,
            bpm = 135,
            gear = 3
        )
        val (wattsHighGear, _) = physics.computePowerWatts(
            mode = "ANKLE",
            rpm = 85f,
            bpm = 135,
            gear = 10
        )
        assertTrue("Expected gear 10 ($wattsHighGear W) > gear 3 ($wattsLowGear W)", wattsHighGear > wattsLowGear)
    }

    @Test
    fun wristModeHeartRateProducesVirtualPowerAndSyntheticCadence() {
        val physics = CyclingPhysics()
        val (wattsRest, rpmRest) = physics.computePowerWatts(
            mode = "WRIST",
            rpm = 0f,
            bpm = 62,
            gear = 7
        )
        assertEquals(0f, wattsRest, 0.01f)
        assertEquals(0f, rpmRest, 0.01f)

        val (wattsActive, rpmActive) = physics.computePowerWatts(
            mode = "WRIST",
            rpm = 0f,
            bpm = 155,
            gear = 7
        )
        assertTrue("Expected active wrist HR power > 100W, got $wattsActive", wattsActive > 100f)
        assertTrue("Expected synthetic RPM > 60, got $rpmActive", rpmActive >= 60f)
    }

    @Test
    fun uphillGradeReducesSteadySpeedComparedToFlatRoad() {
        val flatPhysics = CyclingPhysics()
        val hillPhysics = CyclingPhysics()

        repeat(100) {
            flatPhysics.step(
                dtSeconds = 0.2f,
                mode = "ANKLE",
                rpm = 88f,
                bpm = 145,
                gear = 8,
                overrideGradePercent = 0.0f
            )
            hillPhysics.step(
                dtSeconds = 0.2f,
                mode = "ANKLE",
                rpm = 88f,
                bpm = 145,
                gear = 8,
                overrideGradePercent = 7.0f
            )
        }

        assertTrue(
            "Expected flat speed (${flatPhysics.currentSpeedKmh}) > uphill speed (${hillPhysics.currentSpeedKmh})",
            flatPhysics.currentSpeedKmh > hillPhysics.currentSpeedKmh
        )
    }

    @Test
    fun telemetryWirePayloadParsesCorrectly() {
        val parsed = TelemetryReceiver.parsePayload("v1|ANKLE|88.5|146|1730100000123")
        assertNotNull(parsed)
        assertEquals("ANKLE", parsed?.mode)
        assertEquals(88.5f, parsed?.rpm ?: 0f, 0.01f)
        assertEquals(146, parsed?.bpm)
    }
}
