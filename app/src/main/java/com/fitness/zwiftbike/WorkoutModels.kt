package com.fitness.zwiftbike

/**
 * High-level categorization of structured cycling workouts.
 */
enum class WorkoutType {
    RAMP_TEST,
    SWEET_SPOT,
    VO2_MAX,
    THRESHOLD,
    RECOVERY,
    CUSTOM
}

/**
 * An individual interval step inside a structured workout.
 *
 * @param index 0-based position in the sequence.
 * @param name Short descriptive label (e.g., "Warmup", "Work Interval", "Recovery").
 * @param durationSeconds Length of the step in seconds.
 * @param targetPowerFraction Target power as a fraction of FTP (e.g. 0.88f = 88% of FTP).
 * @param targetPowerFractionHigh Optional high target power fraction for ramp intervals.
 * @param targetCadenceRpm Optional target pedaling cadence in RPM.
 * @param zone Target power zone (1 = Active Recovery, 2 = Endurance, 3 = Tempo, 4 = Threshold, 5 = VO2 Max / Anaerobic).
 */
data class WorkoutStep(
    val index: Int,
    val name: String,
    val durationSeconds: Int,
    val targetPowerFraction: Float,
    val targetPowerFractionHigh: Float? = null,
    val targetCadenceRpm: Int? = null,
    val zone: Int = 1
)

/**
 * Structured cycling workout composed of sequential steps.
 */
data class Workout(
    val id: String,
    val name: String,
    val description: String,
    val category: WorkoutType,
    val steps: List<WorkoutStep>,
    val totalDurationSeconds: Int = steps.sumOf { it.durationSeconds }
)

/**
 * Live execution state of an ongoing structured workout.
 */
data class WorkoutProgress(
    val workout: Workout,
    val currentStepIndex: Int = 0,
    val stepElapsedSeconds: Int = 0,
    val totalElapsedSeconds: Int = 0,
    val targetWatts: Int = 0,
    val targetCadenceRpm: Int = 0,
    val currentStepCompliancePercent: Float = 100f,
    val earnedStars: Int = 0,
    val maxStarsPossible: Int = 0,
    val intensityBiasPercent: Int = 0, // e.g. -5, 0, +5
    val isPaused: Boolean = false,
    val isCompleted: Boolean = false,
    val pacingPrompt: String = "ON TARGET"
) {
    val currentStep: WorkoutStep?
        get() = workout.steps.getOrNull(currentStepIndex)

    val stepRemainingSeconds: Int
        get() = maxOf(0, (currentStep?.durationSeconds ?: 0) - stepElapsedSeconds)

    val totalRemainingSeconds: Int
        get() = maxOf(0, workout.totalDurationSeconds - totalElapsedSeconds)

    val overallProgressPercent: Float
        get() = if (workout.totalDurationSeconds > 0) {
            (totalElapsedSeconds.toFloat() / workout.totalDurationSeconds.toFloat() * 100f).coerceIn(0f, 100f)
        } else {
            0f
        }
}

/**
 * Post-workout analytics and summary report.
 */
data class WorkoutSummary(
    val workoutName: String,
    val category: WorkoutType,
    val totalTimeSeconds: Int,
    val avgWatts: Int,
    val maxWatts: Int,
    val normalizedPowerWatts: Int,
    val intensityFactor: Float,
    val tss: Int,
    val timeInZones: Map<Int, Int>, // Zone 1..5 -> seconds spent
    val overallCompliancePercent: Float,
    val totalStarsEarned: Int,
    val maxStarsPossible: Int,
    val detectedFtpWatts: Int? = null,
    val previousFtpWatts: Int = 220
)
