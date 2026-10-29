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

    @Test
    fun alpeDhuezSteepClimbAcceleratesFromRestInDemoAndLowPower() {
        val physics = CyclingPhysics()
        // Simulate Alpe d'Huez +15.26% starting grade at demo cadence (86 RPM, Gear 7)
        repeat(20) {
            physics.step(
                dtSeconds = 0.2f,
                mode = "ANKLE",
                rpm = 86f,
                bpm = 144,
                gear = 7,
                overrideGradePercent = 15.26f
            )
        }
        assertTrue("Speed on Alpe d'Huez should exceed 5 km/h, got ${physics.currentSpeedKmh}", physics.currentSpeedKmh > 5f)
        assertTrue("Distance should advance, got ${physics.totalDistanceMeters}", physics.totalDistanceMeters > 2f)

        // Verify hybrid fallback: Ankle mode with zero RPM but live HR produces power
        val (fallbackWatts, _) = physics.computePowerWatts(mode = "ANKLE", rpm = 0f, bpm = 135, gear = 7)
        assertTrue("Expected hybrid HR fallback watts > 50W, got $fallbackWatts", fallbackWatts > 50f)

        // Verify hybrid fallback: Wrist mode with movement (gyro RPM) produces power even if HR is low
        val (motionWatts, _) = physics.computePowerWatts(mode = "WRIST", rpm = 80f, bpm = 60, gear = 7)
        assertTrue("Expected hybrid motion watts > 50W, got $motionWatts", motionWatts > 50f)
    }

    @Test
    fun updateProfileUpdatesRiderMassAndCalculations() {
        val physics = CyclingPhysics()
        physics.updateProfile(
            riderWeightKg = 80f,
            bikeWeightKg = 10f,
            ftpWatts = 250f,
            restingHr = 55f,
            maxHr = 185f
        )
        assertEquals(80f, physics.riderWeightKg, 0.01f)
        assertEquals(10f, physics.bikeWeightKg, 0.01f)
        assertEquals(250f, physics.ftpWatts, 0.01f)
        assertEquals(55f, physics.restingHr, 0.01f)
        assertEquals(185f, physics.maxHr, 0.01f)

        val res = physics.step(
            dtSeconds = 0.5f,
            mode = "ANKLE",
            rpm = 90f,
            bpm = 150,
            gear = 8,
            overrideGradePercent = 0f
        )
        assertEquals(res.watts / 80f, res.wattsPerKg, 0.02f)
    }

    @Test
    fun physiological5ZoneCalculationsMatchThresholds() {
        val ftp = 200f
        assertEquals(1, CyclingPhysics.computePowerZone(100, ftp)) // 50% < 55% -> Z1
        assertEquals(2, CyclingPhysics.computePowerZone(140, ftp)) // 70% <= 75% -> Z2
        assertEquals(3, CyclingPhysics.computePowerZone(160, ftp)) // 80% <= 90% -> Z3
        assertEquals(4, CyclingPhysics.computePowerZone(200, ftp)) // 100% <= 105% -> Z4
        assertEquals(5, CyclingPhysics.computePowerZone(220, ftp)) // 110% > 105% -> Z5

        val maxHr = 200f
        assertEquals(1, CyclingPhysics.computeHrZone(100, maxHr)) // 50% < 60% -> Z1
        assertEquals(2, CyclingPhysics.computeHrZone(130, maxHr)) // 65% <= 70% -> Z2
        assertEquals(3, CyclingPhysics.computeHrZone(150, maxHr)) // 75% <= 80% -> Z3
        assertEquals(4, CyclingPhysics.computeHrZone(170, maxHr)) // 85% <= 90% -> Z4
        assertEquals(5, CyclingPhysics.computeHrZone(190, maxHr)) // 95% > 90% -> Z5

        assertEquals("Z1 REC", CyclingPhysics.zoneShortName(1))
        assertEquals("Z5 MAX", CyclingPhysics.zoneShortName(5))
    }

    @Test
    fun autoCalculatorFormulasWorkAccurately() {
        // Tanaka formula: 208 - (0.7 * 30) = 208 - 21 = 187
        assertEquals(187, CyclingPhysics.estimateMaxHrFromAge(30))
        // 208 - (0.7 * 40) = 208 - 28 = 180
        assertEquals(180, CyclingPhysics.estimateMaxHrFromAge(40))

        // FTP: 75kg
        assertEquals(150, CyclingPhysics.estimateFtpFromWeightAndLevel(75f, "BEGINNER")) // 75 * 2.0 = 150
        assertEquals(210, CyclingPhysics.estimateFtpFromWeightAndLevel(75f, "INTERMEDIATE")) // 75 * 2.8 = 210
        assertEquals(270, CyclingPhysics.estimateFtpFromWeightAndLevel(75f, "ADVANCED")) // 75 * 3.6 = 270
    }
}
