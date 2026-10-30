package com.fitness.zwiftbike

import android.util.Log

/**
 * Cloud persistence and observability models for Firebase Firestore, Crashlytics, and Remote Config.
 */
data class GhostRiderRecord(
    val riderId: String,
    val displayName: String,
    val wPerKg: Float,
    val speedMetersPerSec: Float,
    val distanceMeters: Float,
    val routeId: String,
    val recordedEpochMs: Long = System.currentTimeMillis()
) {
    fun toJsonString(): String {
        val safeName = displayName.replace("\"", "\\\"")
        return """{"riderId":"$riderId","displayName":"$safeName","wPerKg":$wPerKg,"speedMetersPerSec":$speedMetersPerSec,"distanceMeters":$distanceMeters,"routeId":"$routeId","recordedEpochMs":$recordedEpochMs}"""
    }

    companion object {
        fun fromJsonString(str: String): GhostRiderRecord? {
            return runCatching {
                val riderId = Regex(""""riderId"\s*:\s*"([^"]+)"""").find(str)?.groupValues?.get(1) ?: return null
                val name = Regex(""""displayName"\s*:\s*"([^"]+)"""").find(str)?.groupValues?.get(1) ?: return null
                val wKg = Regex(""""wPerKg"\s*:\s*([0-9.]+)""").find(str)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
                val spd = Regex(""""speedMetersPerSec"\s*:\s*([0-9.]+)""").find(str)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
                val dist = Regex(""""distanceMeters"\s*:\s*([0-9.]+)""").find(str)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
                val route = Regex(""""routeId"\s*:\s*"([^"]+)"""").find(str)?.groupValues?.get(1) ?: return null
                val epoch = Regex(""""recordedEpochMs"\s*:\s*(\d+)""").find(str)?.groupValues?.get(1)?.toLongOrNull() ?: System.currentTimeMillis()
                GhostRiderRecord(riderId, name, wKg, spd, dist, route, epoch)
            }.getOrNull()
        }
    }
}

/**
 * Manager handling Firebase Crashlytics observability and Remote Config physics tuning.
 */
object FirebaseConfig {

    private const val TAG = "FirebaseConfig"

    @Volatile
    var currentPhysicsConstants: PhysicsConstants = PhysicsConstants()
        private set

    /**
     * Updates physics constants from a key-value configuration map (e.g. from Firebase Remote Config).
     */
    fun applyRemoteConfig(config: Map<String, String>): PhysicsConstants {
        val cda = config["cda_aero"]?.toFloatOrNull() ?: currentPhysicsConstants.cdaAero
        val crr = config["crr_rolling"]?.toFloatOrNull() ?: currentPhysicsConstants.crrRolling
        val rho = config["air_density_rho"]?.toFloatOrNull() ?: currentPhysicsConstants.airDensityRho
        val draft = config["drafting_factor"]?.toFloatOrNull() ?: currentPhysicsConstants.draftingAeroFactor
        val trainerDiff = config["trainer_difficulty"]?.toFloatOrNull() ?: currentPhysicsConstants.trainerDifficultyFactor

        currentPhysicsConstants = PhysicsConstants(
            cdaAero = cda.coerceIn(0.15f, 0.60f),
            crrRolling = crr.coerceIn(0.001f, 0.015f),
            airDensityRho = rho.coerceIn(1.0f, 1.4f),
            draftingAeroFactor = draft.coerceIn(0.50f, 0.95f),
            trainerDifficultyFactor = trainerDiff.coerceIn(0.10f, 1.0f)
        )
        safeLog(4, TAG, "Applied Remote Config physics: $currentPhysicsConstants")
        return currentPhysicsConstants
    }

    /**
     * Records telemetry diagnostics and non-fatal exceptions for Firebase Crashlytics.
     */
    fun logCrashlyticsBreadcrumb(tag: String, message: String) {
        safeLog(3, "CrashlyticsBreadcrumb", "[$tag] $message")
    }

    /**
     * Records non-fatal exceptions (e.g. WebView WebGL context loss, Bluetooth timeout).
     */
    fun recordNonFatalException(throwable: Throwable, contextTag: String) {
        safeLog(5, TAG, "Non-fatal event logged for Crashlytics: [$contextTag] ${throwable.message}", throwable)
    }

    /**
     * Serializes a list of Ghost Rider records for Cloud Firestore document storage.
     */
    fun serializeGhostRiders(records: List<GhostRiderRecord>): String {
        return records.joinToString(prefix = "[", postfix = "]", separator = ",") { it.toJsonString() }
    }

    /**
     * Deserializes Ghost Rider records from Cloud Firestore document storage.
     */
    fun deserializeGhostRiders(jsonString: String): List<GhostRiderRecord> {
        val list = mutableListOf<GhostRiderRecord>()
        val itemRegex = Regex("""\{[^{}]*\}""")
        for (match in itemRegex.findAll(jsonString)) {
            GhostRiderRecord.fromJsonString(match.value)?.let { list.add(it) }
        }
        return list
    }

    private fun safeLog(priority: Int, tag: String, message: String, tr: Throwable? = null) {
        runCatching {
            when (priority) {
                3 -> Log.d(tag, message)
                5 -> Log.w(tag, message, tr)
                else -> Log.i(tag, message)
            }
        }.onFailure {
            println("[$tag] $message")
        }
    }
}
