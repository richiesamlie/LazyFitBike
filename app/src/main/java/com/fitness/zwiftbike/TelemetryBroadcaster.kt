package com.fitness.zwiftbike

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Real-time spectator telemetry broadcaster.
 * Transmits live cyclist metrics (Watts, HR, Cadence, Speed, 3D GPS track coordinates)
 * to a Vercel spectator companion endpoint or local telemetry relay.
 */
data class SpectatorPacket(
    val riderName: String = "LazyFit Cyclist",
    val timestampMs: Long = System.currentTimeMillis(),
    val powerWatts: Int = 0,
    val heartRateBpm: Int = 0,
    val cadenceRpm: Int = 0,
    val speedKmh: Float = 0f,
    val distanceKm: Float = 0f,
    val slopeGradePercent: Float = 0f,
    val normalizedPowerWatts: Int = 0,
    val powerZone: Int = 1,
    val hrZone: Int = 1,
    val caloriesKcal: Int = 0,
    val routeName: String = "Watopia Flat",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val elevationMeters: Double = 0.0,
    val workoutActive: Boolean = false,
    val workoutName: String = "",
    val workoutStepName: String = "",
    val workoutTargetWatts: Int = 0,
    val workoutRemainingSec: Int = 0
)

object TelemetryBroadcaster {

    private val lastBroadcastTimeMs = AtomicLong(0L)
    private const val MIN_BROADCAST_INTERVAL_MS = 950L

    /**
     * Serializes a SpectatorPacket into a standard JSON string without unmocked Android dependencies.
     */
    fun toJson(packet: SpectatorPacket): String {
        return buildString {
            append("{")
            append("\"riderName\":\"").append(escapeJson(packet.riderName)).append("\",")
            append("\"timestampMs\":").append(packet.timestampMs).append(",")
            append("\"powerWatts\":").append(packet.powerWatts).append(",")
            append("\"heartRateBpm\":").append(packet.heartRateBpm).append(",")
            append("\"cadenceRpm\":").append(packet.cadenceRpm).append(",")
            append("\"speedKmh\":").append(String.format(Locale.US, "%.2f", packet.speedKmh)).append(",")
            append("\"distanceKm\":").append(String.format(Locale.US, "%.3f", packet.distanceKm)).append(",")
            append("\"slopeGradePercent\":").append(String.format(Locale.US, "%.1f", packet.slopeGradePercent)).append(",")
            append("\"normalizedPowerWatts\":").append(packet.normalizedPowerWatts).append(",")
            append("\"powerZone\":").append(packet.powerZone).append(",")
            append("\"hrZone\":").append(packet.hrZone).append(",")
            append("\"caloriesKcal\":").append(packet.caloriesKcal).append(",")
            append("\"routeName\":\"").append(escapeJson(packet.routeName)).append("\",")
            append("\"lat\":").append(String.format(Locale.US, "%.6f", packet.lat)).append(",")
            append("\"lon\":").append(String.format(Locale.US, "%.6f", packet.lon)).append(",")
            append("\"elevationMeters\":").append(String.format(Locale.US, "%.1f", packet.elevationMeters)).append(",")
            append("\"workoutActive\":").append(packet.workoutActive).append(",")
            append("\"workoutName\":\"").append(escapeJson(packet.workoutName)).append("\",")
            append("\"workoutStepName\":\"").append(escapeJson(packet.workoutStepName)).append("\",")
            append("\"workoutTargetWatts\":").append(packet.workoutTargetWatts).append(",")
            append("\"workoutRemainingSec\":").append(packet.workoutRemainingSec)
            append("}")
        }
    }

    /**
     * Deserializes a SpectatorPacket JSON string with fallback regex matching.
     */
    fun fromJson(json: String): SpectatorPacket? {
        return runCatching {
            fun extractStr(key: String): String {
                val match = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)
                return match?.groupValues?.get(1) ?: ""
            }
            fun extractInt(key: String, default: Int = 0): Int {
                val match = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)
                return match?.groupValues?.get(1)?.toIntOrNull() ?: default
            }
            fun extractLong(key: String, default: Long = 0L): Long {
                val match = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)
                return match?.groupValues?.get(1)?.toLongOrNull() ?: default
            }
            fun extractFloat(key: String, default: Float = 0f): Float {
                val match = Regex("\"$key\"\\s*:\\s*(-?[0-9.]+)").find(json)
                return match?.groupValues?.get(1)?.toFloatOrNull() ?: default
            }
            fun extractDouble(key: String, default: Double = 0.0): Double {
                val match = Regex("\"$key\"\\s*:\\s*(-?[0-9.]+)").find(json)
                return match?.groupValues?.get(1)?.toDoubleOrNull() ?: default
            }
            fun extractBool(key: String, default: Boolean = false): Boolean {
                val match = Regex("\"$key\"\\s*:\\s*(true|false)").find(json)
                return match?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: default
            }

            SpectatorPacket(
                riderName = extractStr("riderName").ifEmpty { "LazyFit Cyclist" },
                timestampMs = extractLong("timestampMs", System.currentTimeMillis()),
                powerWatts = extractInt("powerWatts"),
                heartRateBpm = extractInt("heartRateBpm"),
                cadenceRpm = extractInt("cadenceRpm"),
                speedKmh = extractFloat("speedKmh"),
                distanceKm = extractFloat("distanceKm"),
                slopeGradePercent = extractFloat("slopeGradePercent"),
                normalizedPowerWatts = extractInt("normalizedPowerWatts"),
                powerZone = extractInt("powerZone", 1),
                hrZone = extractInt("hrZone", 1),
                caloriesKcal = extractInt("caloriesKcal"),
                routeName = extractStr("routeName").ifEmpty { "Watopia Flat" },
                lat = extractDouble("lat"),
                lon = extractDouble("lon"),
                elevationMeters = extractDouble("elevationMeters"),
                workoutActive = extractBool("workoutActive"),
                workoutName = extractStr("workoutName"),
                workoutStepName = extractStr("workoutStepName"),
                workoutTargetWatts = extractInt("workoutTargetWatts"),
                workoutRemainingSec = extractInt("workoutRemainingSec")
            )
        }.getOrNull()
    }

    /**
     * Broadcasts telemetry to a designated spectator endpoint (e.g. Vercel /api/telemetry).
     * Synchronous; call on IO dispatcher.
     */
    fun broadcast(
        spectatorEndpoint: String,
        packet: SpectatorPacket,
        connectTimeoutMs: Int = 1200,
        readTimeoutMs: Int = 1200
    ): Boolean {
        if (spectatorEndpoint.isBlank() || (!spectatorEndpoint.startsWith("http://") && !spectatorEndpoint.startsWith("https://"))) {
            return false
        }

        val now = System.currentTimeMillis()
        val last = lastBroadcastTimeMs.get()
        if (now - last < MIN_BROADCAST_INTERVAL_MS) {
            return false // Throttled
        }
        lastBroadcastTimeMs.set(now)

        return runCatching {
            val url = URL(spectatorEndpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                this.connectTimeout = connectTimeoutMs
                this.readTimeout = readTimeoutMs
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("User-Agent", "LazyFitBike-Android/1.4.0")
                setRequestProperty("X-LazyFit-Device", "Android-Phone")
            }

            val jsonPayload = toJson(packet)
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonPayload)
                writer.flush()
            }

            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..299
        }.getOrDefault(false)
    }

    /**
     * Asynchronously fires a broadcast packet on Dispatchers.IO.
     */
    fun broadcastAsync(
        scope: CoroutineScope,
        spectatorEndpoint: String,
        packet: SpectatorPacket,
        onResult: (Boolean) -> Unit = {}
    ) {
        if (spectatorEndpoint.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val success = broadcast(spectatorEndpoint, packet)
            onResult(success)
        }
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
