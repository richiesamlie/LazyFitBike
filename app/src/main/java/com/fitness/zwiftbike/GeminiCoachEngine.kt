package com.fitness.zwiftbike

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class AthleteCoachTelemetry(
    val currentWatts: Int,
    val targetWatts: Int,
    val currentRpm: Int,
    val targetRpm: Int,
    val currentBpm: Int,
    val ftpWatts: Int,
    val stepRemainingSec: Int,
    val stepName: String,
    val slopeGradePercent: Float,
    val isDrafting: Boolean
)

/**
 * AI Cycling Coach engine leveraging Gemini sports science heuristics
 * for real-time adaptive pacing cues, conversational workout synthesis,
 * and post-ride physiological debriefs.
 */
object GeminiCoachEngine {

    /**
     * Generates a context-aware, real-time coaching cue based on current athletic telemetry.
     */
    fun generateLiveCoachingCue(telemetry: AthleteCoachTelemetry): String {
        val wattsDelta = telemetry.currentWatts - telemetry.targetWatts
        val rpmDelta = telemetry.currentRpm - telemetry.targetRpm

        // 1. Drafting Alert
        if (telemetry.isDrafting) {
            return "In the slipstream! Drafting bonus active (-25% aero drag). Maintain steady power."
        }

        // 2. High Cardiac Strain Warning (approaching maximal threshold)
        if (telemetry.currentBpm >= 185) {
            return "Heart rate near ceiling (${telemetry.currentBpm} BPM). Focus on relaxed breathing and fluid pedal strokes."
        }

        // 3. Cadence grinding under load (protect knees & neuromuscular efficiency)
        if (telemetry.currentRpm in 1..74 && telemetry.targetWatts >= (telemetry.ftpWatts * 0.85f).toInt()) {
            return "Cadence low (${telemetry.currentRpm} RPM). Shift to an easier gear and spin up towards ${telemetry.targetRpm} RPM."
        }

        // 4. Overpowering recovery / warm-up intervals
        if (telemetry.targetWatts <= (telemetry.ftpWatts * 0.60f).toInt() && wattsDelta > 30) {
            return "Easing up! You're riding +${wattsDelta}W above recovery ceiling. Save your legs for the work intervals."
        }

        // 5. Significant underpower during active work interval
        if (wattsDelta < -25 && telemetry.stepRemainingSec > 5) {
            val needed = abs(wattsDelta)
            return if (telemetry.stepRemainingSec <= 30) {
                "Final ${telemetry.stepRemainingSec}s! Dig deep and bridge the +${needed}W gap to the target line."
            } else {
                "Target is ${telemetry.targetWatts}W (+${needed}W needed). Smooth out the pedal stroke and build power."
            }
        }

        // 6. Finishing interval sprint / countdown
        if (telemetry.stepRemainingSec in 1..10) {
            return "Final ${telemetry.stepRemainingSec}s of ${telemetry.stepName}! Hold strong to the tone."
        }

        // 7. Optimal locked-in rhythm
        if (abs(wattsDelta) <= 12 && abs(rpmDelta) <= 8) {
            return "Locked in! Excellent target compliance. Keep this cadence steady."
        }

        // 8. Steep climbing cue
        if (telemetry.slopeGradePercent >= 6.0f) {
            return "Steep grade (+${String.format(Locale.US, "%.1f", telemetry.slopeGradePercent)}%). Keep upper body relaxed and spin smoothly."
        }

        return "Hold the target zone (${telemetry.targetWatts}W @ ${telemetry.targetRpm} RPM)."
    }

    /**
     * Synthesizes a structured [Workout] from a natural language user prompt.
     * Translates concepts like "sweet spot", "VO2 max", "intervals", and durations into Coggan steps.
     */
    fun synthesizeWorkoutFromPrompt(prompt: String, ftpWatts: Int): Workout {
        val lower = prompt.lowercase()
        val durationMins = Regex("""(\d+)\s*(?:min|minute)""").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 45

        val steps = mutableListOf<WorkoutStep>()
        var idx = 0

        // Warmup: 5 minutes @ 50%
        val warmupDuration = (durationMins * 0.15f).roundToInt().coerceIn(3, 8) * 60
        steps.add(
            WorkoutStep(
                index = idx++,
                name = "Warmup",
                durationSeconds = warmupDuration,
                targetPowerFraction = 0.50f,
                targetCadenceRpm = 85,
                zone = 1
            )
        )

        val isVo2 = lower.contains("vo2") || lower.contains("attack") || lower.contains("burst")
        val isThreshold = lower.contains("threshold") || lower.contains("ftp") || lower.contains("over-under")
        val isSweetSpot = lower.contains("sweet") || lower.contains("tempo") || (!isVo2 && !isThreshold)

        val mainDurationSec = (durationMins * 60) - warmupDuration - 300 // Reserve 5 min cooldown

        if (isVo2) {
            // Microbursts: 30s on @ 120%, 30s off @ 50%
            val repeatCount = (mainDurationSec / 60).coerceIn(4, 20)
            for (r in 1..repeatCount) {
                steps.add(
                    WorkoutStep(
                        index = idx++,
                        name = "VO2 Surge $r/$repeatCount",
                        durationSeconds = 30,
                        targetPowerFraction = 1.20f,
                        targetCadenceRpm = 100,
                        zone = 5
                    )
                )
                steps.add(
                    WorkoutStep(
                        index = idx++,
                        name = "Recovery $r/$repeatCount",
                        durationSeconds = 30,
                        targetPowerFraction = 0.50f,
                        targetCadenceRpm = 85,
                        zone = 1
                    )
                )
            }
        } else if (isThreshold) {
            // Over-Unders: 2 min under @ 90%, 1 min over @ 105%
            val repeatCount = (mainDurationSec / 180).coerceIn(2, 6)
            for (r in 1..repeatCount) {
                steps.add(
                    WorkoutStep(
                        index = idx++,
                        name = "Under $r/$repeatCount",
                        durationSeconds = 120,
                        targetPowerFraction = 0.90f,
                        targetCadenceRpm = 90,
                        zone = 4
                    )
                )
                steps.add(
                    WorkoutStep(
                        index = idx++,
                        name = "Over $r/$repeatCount",
                        durationSeconds = 60,
                        targetPowerFraction = 1.05f,
                        targetCadenceRpm = 95,
                        zone = 4
                    )
                )
            }
        } else {
            // Sweet Spot: 2 blocks @ 88-90% with 3 min recovery
            val blockSec = ((mainDurationSec - 180) / 2).coerceIn(300, 1200)
            steps.add(
                WorkoutStep(
                    index = idx++,
                    name = "Sweet Spot Block 1",
                    durationSeconds = blockSec,
                    targetPowerFraction = 0.88f,
                    targetCadenceRpm = 90,
                    zone = 3
                )
            )
            steps.add(
                WorkoutStep(
                    index = idx++,
                    name = "Spin Recovery",
                    durationSeconds = 180,
                    targetPowerFraction = 0.50f,
                    targetCadenceRpm = 85,
                    zone = 1
                )
            )
            steps.add(
                WorkoutStep(
                    index = idx++,
                    name = "Sweet Spot Block 2",
                    durationSeconds = blockSec,
                    targetPowerFraction = 0.90f,
                    targetCadenceRpm = 90,
                    zone = 4
                )
            )
        }

        // Cooldown: 300s @ 40%
        steps.add(
            WorkoutStep(
                index = idx++,
                name = "Cooldown",
                durationSeconds = 300,
                targetPowerFraction = 0.40f,
                targetCadenceRpm = 80,
                zone = 1
            )
        )

        val totalSec = steps.sumOf { it.durationSeconds }
        return Workout(
            id = "gemini_ai_${System.currentTimeMillis()}",
            name = "AI: $prompt",
            description = "Intelligently synthesized session adapted for FTP ${ftpWatts}W ($durationMins min total).",
            category = WorkoutType.CUSTOM,
            totalDurationSeconds = totalSec,
            steps = steps
        )
    }

    /**
     * Produces a personalized post-ride physiological analysis and recovery recommendation.
     */
    fun generatePostRideDebrief(summary: WorkoutSummary, riderWeightKg: Float): String {
        val wPerKg = summary.normalizedPowerWatts / riderWeightKg.coerceAtLeast(40f)
        val sb = StringBuilder()

        sb.append(String.format(Locale.US, "Intensity Factor: %.2f (Normalized Power %dW / %.1f W/kg). ", summary.intensityFactor, summary.normalizedPowerWatts, wPerKg))

        when {
            summary.tss >= 100 -> {
                sb.append("Epic training dose (TSS ${summary.tss}). Expect substantial glycogen depletion; plan a 48-hour recovery window with high carbohydrate refuel.")
            }
            summary.tss >= 60 -> {
                sb.append("Solid threshold stimulus (TSS ${summary.tss}). Optimal adaptation trigger. Rest or light active spin tomorrow.")
            }
            else -> {
                sb.append("Aerobic maintenance load (TSS ${summary.tss}). Low fatigue accumulation; you will be fresh to train again tomorrow.")
            }
        }

        if (summary.overallCompliancePercent >= 90f) {
            sb.append(" Outstanding interval discipline (${String.format(Locale.US, "%.1f", summary.overallCompliancePercent)}% target compliance)!")
        } else if (summary.overallCompliancePercent < 75f) {
            sb.append(" Power targets drifted off-schedule (${String.format(Locale.US, "%.1f", summary.overallCompliancePercent)}% compliance). Consider adjusting FTP bias down 3-5% on fatigued days.")
        }

        return sb.toString()
    }
}
