package com.fitness.zwiftbike

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Core engine providing preset workout generation, .ZWO XML file parsing,
 * Coggan cycling metrics (NP, IF, TSS), Ramp Test FTP auto-detection,
 * and live interval compliance evaluation.
 */
object WorkoutEngine {

    // =========================================================================
    // 1. PRESET WORKOUT LIBRARY
    // =========================================================================

    /**
     * Standard Hunter Allen / Coggan FTP Ramp Test:
     * - 5 min progressive warmup (50% FTP)
     * - 1-minute steps incrementing +6% FTP (~15-20W) each minute from 60% up to 160% FTP
     * - 5 min cooldown (40% FTP)
     * Failure is automatically detected when pedaling stops or cadence < 50 RPM.
     */
    fun createRampTestWorkout(): Workout {
        val steps = mutableListOf<WorkoutStep>()
        var idx = 0

        // Warmup: 300s (5m) @ 50%
        steps.add(
            WorkoutStep(
                index = idx++,
                name = "Warmup",
                durationSeconds = 300,
                targetPowerFraction = 0.50f,
                targetCadenceRpm = 85,
                zone = 1
            )
        )

        // Ramp Steps: 60s each, from 60% up to 160% in +6% increments (17 steps)
        var powerFrac = 0.60f
        while (powerFrac <= 1.62f) {
            val stepWattsFraction = ((powerFrac * 100).roundToInt()) / 100f
            val zone = resolveZoneForFraction(stepWattsFraction)
            val stepPct = (stepWattsFraction * 100).roundToInt()
            steps.add(
                WorkoutStep(
                    index = idx++,
                    name = "Step $idx ($stepPct%)",
                    durationSeconds = 60,
                    targetPowerFraction = stepWattsFraction,
                    targetCadenceRpm = 90,
                    zone = zone
                )
            )
            powerFrac += 0.06f
        }

        // Cooldown: 300s (5m) @ 40%
        steps.add(
            WorkoutStep(
                index = idx++,
                name = "Cooldown",
                durationSeconds = 300,
                targetPowerFraction = 0.40f,
                targetCadenceRpm = 85,
                zone = 1
            )
        )

        return Workout(
            id = "preset_ramp_test",
            name = "FTP Ramp Test",
            description = "Progressive 1-minute step test to exhaustion. Peak 1-minute power is automatically used to estimate your updated FTP.",
            category = WorkoutType.RAMP_TEST,
            steps = steps
        )
    }

    /**
     * Sweet Spot 2x20 Minutes:
     * High aerobic adaptation with low physiological fatigue.
     * 10m warmup, 20m @ 88% FTP, 5m recovery, 20m @ 90% FTP, 5m cooldown.
     */
    fun createSweetSpot2x20Workout(): Workout {
        val steps = mutableListOf<WorkoutStep>()
        var idx = 0

        // Warmup: 10 min progressive (50% to 75%)
        steps.add(
            WorkoutStep(
                index = idx++,
                name = "Warmup",
                durationSeconds = 600,
                targetPowerFraction = 0.65f,
                targetPowerFractionHigh = 0.75f,
                targetCadenceRpm = 88,
                zone = 2
            )
        )

        // Interval 1: 20m @ 88% (Sweet Spot Z3/Z4 boundary)
        steps.add(
            WorkoutStep(
                index = idx++,
                name = "Sweet Spot Block 1",
                durationSeconds = 1200,
                targetPowerFraction = 0.88f,
                targetCadenceRpm = 90,
                zone = 3
            )
        )

        // Mid-Rest: 5m @ 50%
        steps.add(
            WorkoutStep(
                index = idx++,
                name = "Recovery Spin",
                durationSeconds = 300,
                targetPowerFraction = 0.50f,
                targetCadenceRpm = 85,
                zone = 1
            )
        )

        // Interval 2: 20m @ 90%
        steps.add(
            WorkoutStep(
                index = idx++,
                name = "Sweet Spot Block 2",
                durationSeconds = 1200,
                targetPowerFraction = 0.90f,
                targetCadenceRpm = 90,
                zone = 3
            )
        )

        // Cooldown: 5m @ 45%
        steps.add(
            WorkoutStep(
                index = idx++,
                name = "Cooldown",
                durationSeconds = 300,
                targetPowerFraction = 0.45f,
                targetCadenceRpm = 80,
                zone = 1
            )
        )

        return Workout(
            id = "preset_sweet_spot_2x20",
            name = "Sweet Spot 2x20 min",
            description = "The gold standard endurance power builder: 2x 20-minute blocks at 88-90% FTP with a 5-minute active recovery valley.",
            category = WorkoutType.SWEET_SPOT,
            steps = steps
        )
    }

    /**
     * 30/30 VO2 Max Attacks:
     * 10m warmup, 2 sets of 5x (30s @ 125% / 30s @ 50%) with 4m tempo between sets, 6m cooldown.
     */
    fun createVo2Max3030Workout(): Workout {
        val steps = mutableListOf<WorkoutStep>()
        var idx = 0

        // Warmup: 600s
        steps.add(
            WorkoutStep(
                index = idx++,
                name = "Warmup",
                durationSeconds = 600,
                targetPowerFraction = 0.60f,
                targetCadenceRpm = 90,
                zone = 2
            )
        )

        // Set 1: 5 repetitions of 30s on / 30s off
        for (i in 1..5) {
            steps.add(
                WorkoutStep(
                    index = idx++,
                    name = "VO2 Surge 1.$i",
                    durationSeconds = 30,
                    targetPowerFraction = 1.25f,
                    targetCadenceRpm = 100,
                    zone = 5
                )
            )
            steps.add(
                WorkoutStep(
                    index = idx++,
                    name = "Rest 1.$i",
                    durationSeconds = 30,
                    targetPowerFraction = 0.50f,
                    targetCadenceRpm = 80,
                    zone = 1
                )
            )
        }

        // Mid-valley recovery: 240s (4m)
        steps.add(
            WorkoutStep(
                index = idx++,
                name = "Mid-Set Flush",
                durationSeconds = 240,
                targetPowerFraction = 0.60f,
                targetCadenceRpm = 88,
                zone = 2
            )
        )

        // Set 2: 5 repetitions of 30s on / 30s off @ 130%
        for (i in 1..5) {
            steps.add(
                WorkoutStep(
                    index = idx++,
                    name = "VO2 Surge 2.$i",
                    durationSeconds = 30,
                    targetPowerFraction = 1.30f,
                    targetCadenceRpm = 102,
                    zone = 5
                )
            )
            steps.add(
                WorkoutStep(
                    index = idx++,
                    name = "Rest 2.$i",
                    durationSeconds = 30,
                    targetPowerFraction = 0.50f,
                    targetCadenceRpm = 80,
                    zone = 1
                )
            )
        }

        // Cooldown: 360s (6m)
        steps.add(
            WorkoutStep(
                index = idx++,
                name = "Cooldown",
                durationSeconds = 360,
                targetPowerFraction = 0.45f,
                targetCadenceRpm = 80,
                zone = 1
            )
        )

        return Workout(
            id = "preset_vo2_max_3030",
            name = "30/30 VO2 Max Attacks",
            description = "High-octane anaerobic capacity builder: 10 high-power 30-second surges at 125-130% FTP with rapid 30-second recovery dips.",
            category = WorkoutType.VO2_MAX,
            steps = steps
        )
    }

    /**
     * Threshold Over-Unders:
     * Alternates 2m @ 95% FTP (under) and 2m @ 105% FTP (over) to train lactate clearance.
     */
    fun createThresholdOverUndersWorkout(): Workout {
        val steps = mutableListOf<WorkoutStep>()
        var idx = 0

        // Warmup: 480s (8m)
        steps.add(
            WorkoutStep(
                index = idx++,
                name = "Warmup",
                durationSeconds = 480,
                targetPowerFraction = 0.65f,
                targetCadenceRpm = 88,
                zone = 2
            )
        )

        // 3 Over-Under sets
        for (block in 1..3) {
            for (rep in 1..3) {
                steps.add(
                    WorkoutStep(
                        index = idx++,
                        name = "Under $block.$rep (95%)",
                        durationSeconds = 120,
                        targetPowerFraction = 0.95f,
                        targetCadenceRpm = 90,
                        zone = 4
                    )
                )
                steps.add(
                    WorkoutStep(
                        index = idx++,
                        name = "Over $block.$rep (105%)",
                        durationSeconds = 120,
                        targetPowerFraction = 1.05f,
                        targetCadenceRpm = 94,
                        zone = 4
                    )
                )
            }
            if (block < 3) {
                steps.add(
                    WorkoutStep(
                        index = idx++,
                        name = "Block Recovery $block",
                        durationSeconds = 240,
                        targetPowerFraction = 0.50f,
                        targetCadenceRpm = 85,
                        zone = 1
                    )
                )
            }
        }

        // Cooldown: 360s (6m)
        steps.add(
            WorkoutStep(
                index = idx++,
                name = "Cooldown",
                durationSeconds = 360,
                targetPowerFraction = 0.45f,
                targetCadenceRpm = 80,
                zone = 1
            )
        )

        return Workout(
            id = "preset_threshold_over_unders",
            name = "Threshold Over-Unders",
            description = "Teaches your body to shuttle and consume lactate under high pedaling stress by oscillating between 95% and 105% FTP.",
            category = WorkoutType.THRESHOLD,
            steps = steps
        )
    }

    /**
     * Active Recovery Spin:
     * 30 minutes light aerobic flush @ 50% FTP with steady cadence.
     */
    fun createActiveRecoverySpinWorkout(): Workout {
        val steps = listOf(
            WorkoutStep(
                index = 0,
                name = "Recovery Spin",
                durationSeconds = 1800, // 30m
                targetPowerFraction = 0.50f,
                targetCadenceRpm = 92,
                zone = 1
            )
        )
        return Workout(
            id = "preset_active_recovery_spin",
            name = "Active Recovery Spin",
            description = "Gentle 30-minute leg spinner at 50% FTP to promote blood flow, clear muscle soreness, and stimulate recovery.",
            category = WorkoutType.RECOVERY,
            steps = steps
        )
    }

    /**
     * Complete list of all 5 built-in preset workouts.
     */
    val presetWorkouts: List<Workout> by lazy {
        listOf(
            createRampTestWorkout(),
            createSweetSpot2x20Workout(),
            createVo2Max3030Workout(),
            createThresholdOverUndersWorkout(),
            createActiveRecoverySpinWorkout()
        )
    }

    fun getPresetById(id: String): Workout {
        return presetWorkouts.firstOrNull { it.id == id } ?: presetWorkouts.first()
    }

    // =========================================================================
    // 2. .ZWO (ZWIFT WORKOUT XML) PARSER
    // =========================================================================

    private val nameRegex = Regex("""<name>([^<]+)</name>""", RegexOption.IGNORE_CASE)
    private val descRegex = Regex("""<description>([^<]+)</description>""", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("""<([a-zA-Z0-9]+)\s+([^>]+)/?>""", RegexOption.IGNORE_CASE)
    private val attrRegex = Regex("""([a-zA-Z0-9]+)\s*=\s*["']([^"']*)["']""")

    /**
     * Parses a standard Zwift `.zwo` XML file into a [Workout] domain model.
     * Uses portable regex attribute extraction that runs identically in both
     * Android runtime and host JVM unit tests without external parser dependencies.
     */
    fun parseZwoWorkout(xmlContent: String): Workout? {
        if (xmlContent.isBlank()) return null

        return runCatching {
            val workoutName = nameRegex.find(xmlContent)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "Imported ZWO Workout"
            val workoutDesc = descRegex.find(xmlContent)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "Imported from .ZWO file"

            val steps = mutableListOf<WorkoutStep>()
            var stepIndex = 0

            for (tagMatch in tagRegex.findAll(xmlContent)) {
                val tagName = tagMatch.groupValues[1].lowercase()
                val attrString = tagMatch.groupValues[2]

                val attrs = mutableMapOf<String, String>()
                for (attrMatch in attrRegex.findAll(attrString)) {
                    attrs[attrMatch.groupValues[1].lowercase()] = attrMatch.groupValues[2]
                }

                when (tagName) {
                    "warmup" -> {
                        val duration = attrs["duration"]?.toIntOrNull() ?: 600
                        val pLow = attrs["powerlow"]?.toFloatOrNull() ?: 0.45f
                        val pHigh = attrs["powerhigh"]?.toFloatOrNull() ?: 0.75f
                        val cad = attrs["cadence"]?.toIntOrNull()
                        val avgPower = (pLow + pHigh) / 2f
                        val zone = resolveZoneForFraction(avgPower)
                        steps.add(
                            WorkoutStep(
                                index = stepIndex++,
                                name = "Warmup",
                                durationSeconds = max(10, duration),
                                targetPowerFraction = pLow,
                                targetPowerFractionHigh = pHigh,
                                targetCadenceRpm = cad,
                                zone = zone
                            )
                        )
                    }
                    "steadystate" -> {
                        val duration = attrs["duration"]?.toIntOrNull() ?: 300
                        val power = attrs["power"]?.toFloatOrNull() ?: 0.80f
                        val cad = attrs["cadence"]?.toIntOrNull()
                        val zone = resolveZoneForFraction(power)
                        steps.add(
                            WorkoutStep(
                                index = stepIndex++,
                                name = "Steady State (${(power * 100).roundToInt()}%)",
                                durationSeconds = max(5, duration),
                                targetPowerFraction = power,
                                targetCadenceRpm = cad,
                                zone = zone
                            )
                        )
                    }
                    "ramp" -> {
                        val duration = attrs["duration"]?.toIntOrNull() ?: 300
                        val pLow = attrs["powerlow"]?.toFloatOrNull() ?: 0.70f
                        val pHigh = attrs["powerhigh"]?.toFloatOrNull() ?: 1.00f
                        val cad = attrs["cadence"]?.toIntOrNull()
                        val avgPower = (pLow + pHigh) / 2f
                        val zone = resolveZoneForFraction(avgPower)
                        steps.add(
                            WorkoutStep(
                                index = stepIndex++,
                                name = "Ramp (${(pLow * 100).roundToInt()}%-${(pHigh * 100).roundToInt()}%)",
                                durationSeconds = max(10, duration),
                                targetPowerFraction = pLow,
                                targetPowerFractionHigh = pHigh,
                                targetCadenceRpm = cad,
                                zone = zone
                            )
                        )
                    }
                    "intervalst" -> {
                        val repeat = attrs["repeat"]?.toIntOrNull() ?: 3
                        val onDuration = attrs["onduration"]?.toIntOrNull() ?: 60
                        val offDuration = attrs["offduration"]?.toIntOrNull() ?: 60
                        val onPower = attrs["onpower"]?.toFloatOrNull() ?: 1.10f
                        val offPower = attrs["offpower"]?.toFloatOrNull() ?: 0.50f
                        val onCad = attrs["cadence"]?.toIntOrNull()
                        val offCad = attrs["cadenceresting"]?.toIntOrNull()

                        val onZone = resolveZoneForFraction(onPower)
                        val offZone = resolveZoneForFraction(offPower)

                        for (r in 1..repeat) {
                            steps.add(
                                WorkoutStep(
                                    index = stepIndex++,
                                    name = "Work $r/$repeat (${(onPower * 100).roundToInt()}%)",
                                    durationSeconds = max(5, onDuration),
                                    targetPowerFraction = onPower,
                                    targetCadenceRpm = onCad,
                                    zone = onZone
                                )
                            )
                            steps.add(
                                WorkoutStep(
                                    index = stepIndex++,
                                    name = "Rest $r/$repeat (${(offPower * 100).roundToInt()}%)",
                                    durationSeconds = max(5, offDuration),
                                    targetPowerFraction = offPower,
                                    targetCadenceRpm = offCad,
                                    zone = offZone
                                )
                            )
                        }
                    }
                    "cooldown" -> {
                        val duration = attrs["duration"]?.toIntOrNull() ?: 300
                        val pLow = attrs["powerlow"]?.toFloatOrNull() ?: 0.60f
                        val pHigh = attrs["powerhigh"]?.toFloatOrNull() ?: 0.40f
                        val cad = attrs["cadence"]?.toIntOrNull()
                        val avgPower = (pLow + pHigh) / 2f
                        val zone = resolveZoneForFraction(avgPower)
                        steps.add(
                            WorkoutStep(
                                index = stepIndex++,
                                name = "Cooldown",
                                durationSeconds = max(10, duration),
                                targetPowerFraction = pLow,
                                targetPowerFractionHigh = pHigh,
                                targetCadenceRpm = cad,
                                zone = zone
                            )
                        )
                    }
                    "freeride" -> {
                        val duration = attrs["duration"]?.toIntOrNull() ?: 300
                        val cad = attrs["cadence"]?.toIntOrNull()
                        steps.add(
                            WorkoutStep(
                                index = stepIndex++,
                                name = "Free Ride",
                                durationSeconds = max(10, duration),
                                targetPowerFraction = 0.65f,
                                targetCadenceRpm = cad,
                                zone = 2
                            )
                        )
                    }
                    "maxeffort" -> {
                        val duration = attrs["duration"]?.toIntOrNull() ?: 60
                        steps.add(
                            WorkoutStep(
                                index = stepIndex++,
                                name = "Max Effort",
                                durationSeconds = max(5, duration),
                                targetPowerFraction = 1.50f,
                                targetCadenceRpm = 105,
                                zone = 5
                            )
                        )
                    }
                }
            }

            if (steps.isEmpty()) return null

            val workoutId = "zwo_" + abs(workoutName.hashCode()).toString(16)
            Workout(
                id = workoutId,
                name = workoutName,
                description = workoutDesc,
                category = WorkoutType.CUSTOM,
                steps = steps
            )
        }.getOrNull()
    }

    // =========================================================================
    // 3. SCIENTIFIC COGGAN POWER TRAINING METRICS
    // =========================================================================

    /**
     * Calculates Normalized Power (NP) in Watts.
     * Follows the official Coggan algorithm:
     * 1. 30-second rolling average of 1 Hz power values.
     * 2. Raise each 30s average to the 4th power.
     * 3. Take the arithmetic mean of all 4th-power values.
     * 4. Take the 4th root of the result.
     * For activities under 30 seconds, returns the simple mean.
     */
    fun computeNormalizedPower(wattsPerSecond: List<Int>): Int {
        if (wattsPerSecond.isEmpty()) return 0
        if (wattsPerSecond.size < 30) {
            return (wattsPerSecond.average()).roundToInt()
        }

        // Rolling 30-second average window
        var rollingSum = 0.0
        for (i in 0 until 30) {
            rollingSum += wattsPerSecond[i]
        }

        val fourthPowers = ArrayList<Double>(wattsPerSecond.size - 29)
        fourthPowers.add((rollingSum / 30.0).pow(4.0))

        for (i in 30 until wattsPerSecond.size) {
            rollingSum += wattsPerSecond[i] - wattsPerSecond[i - 30]
            val rollingAvg = max(0.0, rollingSum / 30.0)
            fourthPowers.add(rollingAvg.pow(4.0))
        }

        if (fourthPowers.isEmpty()) return 0
        val meanFourthPower = fourthPowers.average()
        return meanFourthPower.pow(0.25).roundToInt()
    }

    /**
     * Calculates Intensity Factor (IF):
     * IF = Normalized Power (Watts) / Functional Threshold Power (Watts).
     */
    fun computeIntensityFactor(normalizedPowerWatts: Int, ftpWatts: Int): Float {
        val safeFtp = max(50, ftpWatts)
        return (normalizedPowerWatts.toFloat() / safeFtp.toFloat()).coerceAtLeast(0f)
    }

    /**
     * Calculates Training Stress Score (TSS):
     * TSS = (durationSeconds * NP * IF) / (FTP * 3600) * 100.
     * A 1-hour all-out threshold effort (NP = FTP, IF = 1.0) produces exactly 100 TSS.
     */
    fun computeTss(
        durationSeconds: Int,
        normalizedPowerWatts: Int,
        intensityFactor: Float,
        ftpWatts: Int
    ): Int {
        if (durationSeconds <= 0 || ftpWatts <= 0 || normalizedPowerWatts <= 0) return 0
        val safeFtp = max(50, ftpWatts)
        val score = (durationSeconds.toDouble() * normalizedPowerWatts.toDouble() * intensityFactor.toDouble()) /
                (safeFtp.toDouble() * 3600.0) * 100.0
        return score.roundToInt().coerceAtLeast(0)
    }

    // =========================================================================
    // 4. RAMP TEST FTP ESTIMATOR
    // =========================================================================

    /**
     * Calculates updated FTP from a completed or exhausted Ramp Test:
     * FTP = 75% of peak 60-second rolling power output.
     */
    fun calculateRampTestFtp(wattsPerSecond: List<Int>): Int {
        if (wattsPerSecond.isEmpty()) return 200
        if (wattsPerSecond.size < 60) {
            return (wattsPerSecond.maxOrNull()?.times(0.75f) ?: 200f).roundToInt()
        }

        var rollingSum = 0.0
        for (i in 0 until 60) {
            rollingSum += wattsPerSecond[i]
        }
        var max60sSum = rollingSum

        for (i in 60 until wattsPerSecond.size) {
            rollingSum += wattsPerSecond[i] - wattsPerSecond[i - 60]
            if (rollingSum > max60sSum) {
                max60sSum = rollingSum
            }
        }

        val peak60sAverageWatts = max60sSum / 60.0
        val estimatedFtp = peak60sAverageWatts * 0.75
        return estimatedFtp.roundToInt().coerceIn(60, 550)
    }

    // =========================================================================
    // 5. LIVE INTERVAL COMPLIANCE & PACING
    // =========================================================================

    /**
     * Evaluates live compliance (0% to 100%) against target watts.
     * Target tolerance window is ±10% or ±15W (whichever is larger).
     */
    fun evaluateStepCompliance(actualWatts: Int, targetWatts: Int): Float {
        if (targetWatts <= 0) return 100f
        val delta = abs(actualWatts - targetWatts)
        val tolerance = max(15f, targetWatts * 0.10f)

        return if (delta <= tolerance) {
            100f
        } else {
            val overshoot = delta - tolerance
            val penalty = (overshoot / targetWatts.toFloat()) * 100f
            (100f - penalty).coerceIn(0f, 100f)
        }
    }

    /**
     * Converts an interval's overall compliance percentage into stars (0..3).
     */
    fun starsForCompliance(compliancePercent: Float): Int {
        return when {
            compliancePercent >= 85f -> 3
            compliancePercent >= 65f -> 2
            compliancePercent >= 40f -> 1
            else -> 0
        }
    }

    /**
     * Generates concise, high-visibility cockpit pacing cues.
     */
    fun pacingPrompt(
        actualWatts: Int,
        targetWatts: Int,
        actualRpm: Int,
        targetCadenceRpm: Int?
    ): String {
        if (targetWatts <= 0) return "FREE RIDE"

        // Cadence priority if target cadence is explicitly specified
        if (targetCadenceRpm != null) {
            if (actualRpm < targetCadenceRpm - 12 && actualRpm > 0) {
                return "▲ SPIN FASTER ($targetCadenceRpm RPM)"
            }
            if (actualRpm > targetCadenceRpm + 15) {
                return "▼ LOWER CADENCE ($targetCadenceRpm RPM)"
            }
        }

        val powerDiff = actualWatts - targetWatts
        return when {
            powerDiff < -22 -> "▲ PUSH MORE WATTS"
            powerDiff > 28 -> "▼ EASE OFF"
            else -> "✔ ON TARGET"
        }
    }

    /**
     * Maps target power fraction of FTP to 5 training zones.
     */
    fun resolveZoneForFraction(fraction: Float): Int {
        return when {
            fraction < 0.55f -> 1 // Active Recovery
            fraction <= 0.75f -> 2 // Endurance
            fraction <= 0.90f -> 3 // Tempo
            fraction <= 1.05f -> 4 // Threshold
            else -> 5 // VO2 Max / Anaerobic
        }
    }
}
