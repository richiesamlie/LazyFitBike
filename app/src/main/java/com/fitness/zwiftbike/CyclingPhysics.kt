package com.fitness.zwiftbike

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

data class PhysicsStepResult(
    val watts: Float,
    val wattsPerKg: Float,
    val effectiveRpm: Float,
    val speedKmh: Float,
    val distanceMeters: Float,
    val elevationMeters: Float,
    val slopeGradePercent: Float,
    val caloriesKcal: Float
)

/**
 * Deterministic road cycling physics engine converting either Ankle RPM or Wrist Heart Rate
 * into estimated Power (Watts), Road Speed (km/h), Elevation Gain (m), and Course Slope (%).
 */
class CyclingPhysics(
    val riderWeightKg: Float = 70f,
    val bikeWeightKg: Float = 9f,
    val restingHr: Float = 60f,
    val maxHr: Float = 190f,
    val ftpWatts: Float = 220f
) {
    var currentSpeedKmh: Float = 0f
        private set
    var totalDistanceMeters: Float = 0f
        private set
    var totalElevationMeters: Float = 0f
        private set
    var totalCaloriesKcal: Float = 0f
        private set

    fun computePowerWatts(
        mode: String,
        rpm: Float,
        bpm: Int,
        gear: Int
    ): Pair<Float, Float> {
        val safeGear = gear.coerceIn(1, 12)
        return if (mode == "WRIST") {
            if (bpm < restingHr + 8f) {
                0f to 0f
            } else {
                val hrr = ((bpm - restingHr) / (maxHr - restingHr)).coerceIn(0f, 1.2f)
                val watts = (ftpWatts * hrr.pow(1.35f)).coerceIn(0f, 900f)
                val syntheticRpm = if (watts > 0f) {
                    (55f + (watts / ftpWatts) * 35f).coerceIn(50f, 110f)
                } else {
                    0f
                }
                watts to syntheticRpm
            }
        } else {
            // ANKLE RPM mode
            if (rpm < 5f) {
                0f to 0f
            } else {
                val gearMultiplier = 0.50f + (safeGear * 0.12f)
                val watts = (gearMultiplier * 0.032f * rpm.pow(1.85f)).coerceIn(0f, 1200f)
                watts to rpm.coerceIn(0f, 160f)
            }
        }
    }

    /**
     * Returns road gradient (%) at a given course distance along the 4000m loop.
     */
    fun getCourseGradePercent(distanceMeters: Float): Float {
        val loopPos = ((distanceMeters % 4000f) + 4000f) % 4000f
        return when {
            loopPos < 1000f -> {
                // Flat Velodrome Sprint: 0.0% to +1.2%
                val t = loopPos / 1000f
                (0.6f * sin(t * PI)).toFloat()
            }
            loopPos < 2200f -> {
                // Alpine Switchback Climb: +2.0% to +7.5%
                val t = (loopPos - 1000f) / 1200f
                2.0f + 5.5f * sin(t * PI).toFloat()
            }
            loopPos < 3200f -> {
                // Coastal Ridge Descent: -1.5% to -5.5%
                val t = (loopPos - 2200f) / 1000f
                -1.5f - 4.0f * sin(t * PI).toFloat()
            }
            else -> {
                // Valley Flats: -0.5% to +0.8%
                val t = (loopPos - 3200f) / 800f
                0.4f * sin(t * 2.0 * PI).toFloat()
            }
        }
    }

    fun step(
        dtSeconds: Float,
        mode: String,
        rpm: Float,
        bpm: Int,
        gear: Int,
        isDrafting: Boolean = false,
        overrideGradePercent: Float? = null
    ): PhysicsStepResult {
        val dt = dtSeconds.coerceIn(0.001f, 1.0f)
        val (watts, effectiveRpm) = computePowerWatts(mode, rpm, bpm, gear)
        val gradePercent = overrideGradePercent ?: getCourseGradePercent(totalDistanceMeters)

        val vMs = currentSpeedKmh / 3.6f
        val totalMass = riderWeightKg + bikeWeightKg

        val dragFactor = if (isDrafting) 0.75f else 1.0f
        val fAero = 0.5f * 1.225f * 0.32f * dragFactor * vMs * vMs
        val fRoll = totalMass * 9.81f * 0.004f
        val fGrav = totalMass * 9.81f * (gradePercent / 100f)

        val accelMs2 = if (watts <= 0.5f && gradePercent >= -0.5f) {
            // Smooth coast-down when rider stops pedaling
            -1.2f
        } else {
            val fDrive = watts / max(vMs, 1.5f)
            (fDrive - fAero - fRoll - fGrav) / totalMass
        }

        val nextVMs = (vMs + accelMs2 * dt).coerceIn(0f, 22.2f)
        currentSpeedKmh = if (nextVMs < 0.15f && watts == 0f) 0f else nextVMs * 3.6f

        val deltaDist = (currentSpeedKmh / 3.6f) * dt
        totalDistanceMeters += deltaDist
        if (gradePercent > 0f && deltaDist > 0f) {
            totalElevationMeters += deltaDist * (gradePercent / 100f)
        }

        // Human mechanical efficiency ~24%: 1 Watt-second = 1 Joule ~ 0.000997 kcal of metabolic energy
        totalCaloriesKcal += (watts * dt) * 0.000997f

        return PhysicsStepResult(
            watts = watts,
            wattsPerKg = watts / riderWeightKg,
            effectiveRpm = effectiveRpm,
            speedKmh = currentSpeedKmh,
            distanceMeters = totalDistanceMeters,
            elevationMeters = totalElevationMeters,
            slopeGradePercent = gradePercent,
            caloriesKcal = totalCaloriesKcal
        )
    }

    fun reset() {
        currentSpeedKmh = 0f
        totalDistanceMeters = 0f
        totalElevationMeters = 0f
        totalCaloriesKcal = 0f
    }
}
