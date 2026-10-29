package com.fitness.zwiftbike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutEngineTest {

    @Test
    fun presetWorkoutsArePopulatedAndValid() {
        val presets = WorkoutEngine.presetWorkouts
        assertEquals(5, presets.size)

        // Ramp Test
        val ramp = WorkoutEngine.getPresetById("preset_ramp_test")
        assertEquals(WorkoutType.RAMP_TEST, ramp.category)
        assertTrue("Ramp test must have >= 15 steps", ramp.steps.size >= 15)
        assertEquals("Warmup", ramp.steps.first().name)
        assertEquals("Cooldown", ramp.steps.last().name)
        assertEquals(1, ramp.steps.first().zone)

        // Sweet Spot 2x20
        val sweetSpot = WorkoutEngine.getPresetById("preset_sweet_spot_2x20")
        assertEquals(3600, sweetSpot.totalDurationSeconds) // 60 mins total
        assertEquals(5, sweetSpot.steps.size)
        assertEquals(0.88f, sweetSpot.steps[1].targetPowerFraction, 0.001f)
        assertEquals(0.90f, sweetSpot.steps[3].targetPowerFraction, 0.001f)

        // VO2 Max 30/30
        val vo2 = WorkoutEngine.getPresetById("preset_vo2_max_3030")
        assertTrue("VO2 max workout should contain surge intervals", vo2.steps.any { it.zone == 5 })

        // Threshold Over-Unders
        val overUnders = WorkoutEngine.getPresetById("preset_threshold_over_unders")
        assertTrue("Threshold over-unders should have steps at 0.95 and 1.05",
            overUnders.steps.any { it.targetPowerFraction == 0.95f } &&
            overUnders.steps.any { it.targetPowerFraction == 1.05f }
        )

        // Active Recovery Spin
        val recovery = WorkoutEngine.getPresetById("preset_active_recovery_spin")
        assertEquals(1800, recovery.totalDurationSeconds)
        assertEquals(1, recovery.steps.size)
        assertEquals(1, recovery.steps.first().zone)
    }

    @Test
    fun parseZwoWorkoutParsesZwiftXmlAccurately() {
        val sampleZwo = """
            <workout_file>
              <author>Coach Rich</author>
              <name>Test Microbursts</name>
              <description>Fast high intensity surges</description>
              <sportType>bike</sportType>
              <workout>
                <warmup Duration="300" PowerLow="0.45" PowerHigh="0.75" Cadence="85"/>
                <IntervalsT Repeat="3" OnDuration="40" OffDuration="20" OnPower="1.25" OffPower="0.50" Cadence="105" CadenceResting="80"/>
                <SteadyState Duration="180" Power="0.90" Cadence="90"/>
                <Ramp Duration="240" PowerLow="0.70" PowerHigh="1.00" Cadence="92"/>
                <cooldown Duration="300" PowerLow="0.60" PowerHigh="0.40" Cadence="80"/>
              </workout>
            </workout_file>
        """.trimIndent()

        val workout = WorkoutEngine.parseZwoWorkout(sampleZwo)
        assertNotNull(workout)
        assertEquals("Test Microbursts", workout?.name)
        assertEquals("Fast high intensity surges", workout?.description)
        assertEquals(WorkoutType.CUSTOM, workout?.category)

        val steps = workout!!.steps
        // 1 warmup + (3 * 2 intervals) + 1 steadystate + 1 ramp + 1 cooldown = 10 steps
        assertEquals(10, steps.size)

        // Check Warmup
        assertEquals("Warmup", steps[0].name)
        assertEquals(300, steps[0].durationSeconds)
        assertEquals(0.45f, steps[0].targetPowerFraction, 0.01f)
        assertEquals(0.75f, steps[0].targetPowerFractionHigh ?: 0f, 0.01f)
        assertEquals(85, steps[0].targetCadenceRpm)

        // Check Repeat Intervals (Work 1, Rest 1, Work 2, Rest 2, Work 3, Rest 3)
        assertEquals("Work 1/3 (125%)", steps[1].name)
        assertEquals(40, steps[1].durationSeconds)
        assertEquals(1.25f, steps[1].targetPowerFraction, 0.01f)
        assertEquals(105, steps[1].targetCadenceRpm)
        assertEquals(5, steps[1].zone)

        assertEquals("Rest 1/3 (50%)", steps[2].name)
        assertEquals(20, steps[2].durationSeconds)
        assertEquals(0.50f, steps[2].targetPowerFraction, 0.01f)
        assertEquals(80, steps[2].targetCadenceRpm)
        assertEquals(1, steps[2].zone)

        // Check SteadyState
        assertEquals(180, steps[7].durationSeconds)
        assertEquals(0.90f, steps[7].targetPowerFraction, 0.01f)
        assertEquals(3, steps[7].zone)

        // Check Cooldown
        assertEquals("Cooldown", steps[9].name)
        assertEquals(300, steps[9].durationSeconds)
    }

    @Test
    fun computeNormalizedPowerAccurateOnSteadyAndVariableVectors() {
        // A perfectly steady 200W ride for 60 seconds should have NP = 200W
        val steadyWatts = List(60) { 200 }
        val npSteady = WorkoutEngine.computeNormalizedPower(steadyWatts)
        assertEquals(200, npSteady)

        // Variable ride: NP should exceed average power due to 4th power weighting of surges
        val variableWatts = mutableListOf<Int>()
        repeat(10) {
            variableWatts.addAll(List(30) { 100 }) // 30s @ 100W
            variableWatts.addAll(List(30) { 300 }) // 30s @ 300W
        }
        val avgWatts = variableWatts.average().toInt()
        val npVariable = WorkoutEngine.computeNormalizedPower(variableWatts)
        assertEquals(200, avgWatts)
        assertTrue("Normalized power ($npVariable W) must exceed average power ($avgWatts W) on surges",
            npVariable > avgWatts)
    }

    @Test
    fun computeIntensityFactorAndTssMatchesCogganStandard() {
        val ftp = 250

        // 1 hour steady at 100% FTP (NP = 250W)
        val ifSteady = WorkoutEngine.computeIntensityFactor(250, ftp)
        assertEquals(1.0f, ifSteady, 0.001f)

        val tss1HourThreshold = WorkoutEngine.computeTss(
            durationSeconds = 3600,
            normalizedPowerWatts = 250,
            intensityFactor = 1.0f,
            ftpWatts = ftp
        )
        // 1 hour at FTP = exactly 100 TSS
        assertEquals(100, tss1HourThreshold)

        // 2 hours at 70% FTP (NP = 175W, IF = 0.7)
        val tss2HourEndurance = WorkoutEngine.computeTss(
            durationSeconds = 7200,
            normalizedPowerWatts = 175,
            intensityFactor = 0.70f,
            ftpWatts = ftp
        )
        // (7200 * 175 * 0.70) / (250 * 3600) * 100 = (882000 / 900000) * 100 = 98 TSS
        assertEquals(98, tss2HourEndurance)
    }

    @Test
    fun calculateRampTestFtpDetectsPeak60SecondsAccurately() {
        val testData = mutableListOf<Int>()
        // Warmup: 300s at 100W
        testData.addAll(List(300) { 100 })
        // Step 1: 60s at 150W
        testData.addAll(List(60) { 150 })
        // Step 2: 60s at 200W
        testData.addAll(List(60) { 200 })
        // Step 3: 60s at 250W
        testData.addAll(List(60) { 250 })
        // Step 4: 60s at 300W
        testData.addAll(List(60) { 300 })
        // Step 5: 60s at 360W (Peak 60 seconds)
        testData.addAll(List(60) { 360 })
        // Exhaustion failure and cooldown
        testData.addAll(List(60) { 100 })

        val detectedFtp = WorkoutEngine.calculateRampTestFtp(testData)
        // Peak 60s average = 360W. FTP = 360 * 0.75 = 270W
        assertEquals(270, detectedFtp)
    }

    @Test
    fun evaluateStepComplianceAndStars() {
        val targetWatts = 200

        // Exact match
        val compExact = WorkoutEngine.evaluateStepCompliance(200, targetWatts)
        assertEquals(100f, compExact, 0.01f)
        assertEquals(3, WorkoutEngine.starsForCompliance(compExact))

        // Within tolerance window (±20W for 200W target)
        val compWithin = WorkoutEngine.evaluateStepCompliance(215, targetWatts)
        assertEquals(100f, compWithin, 0.01f)
        assertEquals(3, WorkoutEngine.starsForCompliance(compWithin))

        // Significantly below target
        val compLow = WorkoutEngine.evaluateStepCompliance(120, targetWatts)
        assertTrue("Compliance should be significantly penalized", compLow <= 70f)
        assertEquals(2, WorkoutEngine.starsForCompliance(compLow))

        val compFailing = WorkoutEngine.evaluateStepCompliance(50, targetWatts)
        assertTrue(compFailing < 40f)
        assertEquals(0, WorkoutEngine.starsForCompliance(compFailing))
    }

    @Test
    fun pacingPromptGeneratesAccurateGuidance() {
        // Cadence prompts take precedence
        val cadSlowPrompt = WorkoutEngine.pacingPrompt(
            actualWatts = 200,
            targetWatts = 200,
            actualRpm = 70,
            targetCadenceRpm = 90
        )
        assertTrue(cadSlowPrompt.contains("SPIN FASTER"))

        // Power low
        val powerLowPrompt = WorkoutEngine.pacingPrompt(
            actualWatts = 150,
            targetWatts = 200,
            actualRpm = 90,
            targetCadenceRpm = 90
        )
        assertTrue(powerLowPrompt.contains("PUSH MORE WATTS"))

        // Power high
        val powerHighPrompt = WorkoutEngine.pacingPrompt(
            actualWatts = 260,
            targetWatts = 200,
            actualRpm = 90,
            targetCadenceRpm = 90
        )
        assertTrue(powerHighPrompt.contains("EASE OFF"))

        // On target
        val onTargetPrompt = WorkoutEngine.pacingPrompt(
            actualWatts = 202,
            targetWatts = 200,
            actualRpm = 91,
            targetCadenceRpm = 90
        )
        assertTrue(onTargetPrompt.contains("ON TARGET"))
    }
}
