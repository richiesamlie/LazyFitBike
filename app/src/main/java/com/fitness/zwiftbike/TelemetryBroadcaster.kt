package com.fitness.zwiftbike

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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

data class BroadcastResult(
    val success: Boolean,
    val endpoint: String = "",
    val httpCode: Int = 0,
    val errorMessage: String? = null
)

object TelemetryBroadcaster {

    private val lastBroadcastTimeMs = AtomicLong(0L)
    private val isBroadcastInFlight = AtomicBoolean(false)
    private const val MIN_BROADCAST_INTERVAL_MS = 950L
    private const val DEFAULT_TELEMETRY_PATH = "/api/telemetry"
    private const val DEFAULT_CHEER_PATH = "/api/cheer"
    @Volatile
    private var lastSeenCheerId: String = ""

    /**
     * Normalizes user-entered Spectator URLs so base hostnames or root Vercel URLs
     * (e.g. "richiesamliefitbike.vercel.app" or "https://richiesamliefitbike.vercel.app/")
     * resolve cleanly to "https://richiesamliefitbike.vercel.app/api/telemetry".
     * Local hosts without a scheme default to "http://".
     */
    fun normalizeEndpointUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return ""

        val hasScheme = trimmed.contains("://")
        val initialCandidate = if (hasScheme) trimmed else "http://$trimmed"

        val parsed = runCatching { URI(initialCandidate) }.getOrNull() ?: return ""
        val rawScheme = parsed.scheme?.lowercase(Locale.US) ?: return ""
        if (rawScheme != "http" && rawScheme != "https") return ""

        val host = parsed.host?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
        val isLocal = isLocalOrPrivateHost(host)
        if (!hasScheme && !isLocal && !host.contains('.')) {
            return ""
        }

        val resolvedScheme = when {
            !hasScheme -> if (isLocal) "http" else "https"
            rawScheme == "http" && host.endsWith(".vercel.app", ignoreCase = true) -> "https"
            else -> rawScheme
        }

        val cleanPath = (parsed.rawPath ?: "").trimEnd('/')
        val resolvedPath = if (cleanPath.isEmpty()) DEFAULT_TELEMETRY_PATH else cleanPath
        val portSuffix = if (parsed.port != -1) ":${parsed.port}" else ""
        val querySuffix = parsed.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""

        return "$resolvedScheme://$host$portSuffix$resolvedPath$querySuffix"
    }

    /**
     * Derives the matching /api/cheer endpoint from a normalized telemetry URL.
     */
    fun resolveCheerEndpoint(spectatorEndpoint: String): String? {
        val normalized = normalizeEndpointUrl(spectatorEndpoint)
        if (normalized.isEmpty()) return null
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val path = (uri.rawPath ?: "").trimEnd('/')
        if (!path.endsWith(DEFAULT_TELEMETRY_PATH)) return null
        val cheerPath = path.removeSuffix(DEFAULT_TELEMETRY_PATH) + DEFAULT_CHEER_PATH
        val portSuffix = if (uri.port != -1) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host}$portSuffix$cheerPath"
    }

    private fun isLocalOrPrivateHost(host: String): Boolean {
        val lower = host.lowercase(Locale.US)
        if (lower == "localhost" || lower == "127.0.0.1" || lower == "10.0.2.2" || lower.endsWith(".local")) {
            return true
        }
        val parts = lower.split('.')
        if (parts.size == 4) {
            val first = parts[0].toIntOrNull()
            val second = parts[1].toIntOrNull()
            if (first != null && second != null) {
                if (first == 10) return true
                if (first == 192 && second == 168) return true
                if (first == 172 && second in 16..31) return true
            }
        }
        return false
    }

    /**
     * Parses a /api/cheer JSON payload and returns Pair(cheerId, formattedMessage) for the newest cheer.
     */
    fun parseLatestCheer(json: String): Pair<String, String>? {
        return runCatching {
            val itemRegex = Regex("""\{[^{}]*"id"\s*:\s*"([^"]+)"[^{}]*\}""")
            val lastMatch = itemRegex.findAll(json).lastOrNull() ?: return null
            val block = lastMatch.value
            val id = lastMatch.groupValues[1]
            val sender = Regex(""""sender"\s*:\s*"([^"]*)"""").find(block)?.groupValues?.get(1)?.ifBlank { "Spectator" } ?: "Spectator"
            val msg = Regex(""""message"\s*:\s*"([^"]*)"""").find(block)?.groupValues?.get(1) ?: return null
            if (id.isBlank() || msg.isBlank()) return null
            id to "📣 $sender: $msg"
        }.getOrNull()
    }

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
        connectTimeoutMs: Int = 4000,
        readTimeoutMs: Int = 4000
    ): Boolean {
        return broadcastWithDiagnostics(
            spectatorEndpoint = spectatorEndpoint,
            packet = packet,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        ).success
    }

    /**
     * Broadcasts telemetry and returns detailed HTTP / error diagnostics.
     */
    fun broadcastWithDiagnostics(
        spectatorEndpoint: String,
        packet: SpectatorPacket,
        connectTimeoutMs: Int = 4000,
        readTimeoutMs: Int = 4000,
        bypassThrottle: Boolean = false
    ): BroadcastResult {
        val normalized = normalizeEndpointUrl(spectatorEndpoint)
        if (normalized.isEmpty()) {
            return BroadcastResult(success = false, errorMessage = "Invalid spectator URL")
        }

        val now = System.currentTimeMillis()
        if (!bypassThrottle) {
            val last = lastBroadcastTimeMs.get()
            if (now - last < MIN_BROADCAST_INTERVAL_MS) {
                return BroadcastResult(success = false, endpoint = normalized, errorMessage = "Throttled")
            }
        }
        lastBroadcastTimeMs.set(now)

        val jsonPayload = toJson(packet)
        return runCatching {
            var targetUrl = normalized
            var redirectCount = 0

            while (redirectCount <= 2) {
                val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    instanceFollowRedirects = false
                    this.connectTimeout = connectTimeoutMs
                    this.readTimeout = readTimeoutMs
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "LazyFitBike-Android/${BuildConfig.VERSION_NAME}")
                    setRequestProperty("X-LazyFit-Device", "Android-Phone")
                }

                try {
                    OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(jsonPayload)
                        writer.flush()
                    }

                    val responseCode = connection.responseCode
                    if (responseCode in listOf(301, 302, 307, 308) && redirectCount < 2) {
                        val location = connection.getHeaderField("Location")
                        if (!location.isNullOrBlank()) {
                            targetUrl = URL(URL(targetUrl), location).toString()
                            redirectCount++
                            continue
                        }
                    }

                    if (responseCode in 200..299) {
                        connection.inputStream?.close()
                        return@runCatching BroadcastResult(
                            success = true,
                            endpoint = targetUrl,
                            httpCode = responseCode
                        )
                    }

                    val errSnippet = runCatching {
                        connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?.take(160)
                            ?.trim()
                    }.getOrNull()
                    val detail = if (!errSnippet.isNullOrBlank()) {
                        "HTTP $responseCode ($errSnippet)"
                    } else {
                        "HTTP $responseCode"
                    }
                    return@runCatching BroadcastResult(
                        success = false,
                        endpoint = targetUrl,
                        httpCode = responseCode,
                        errorMessage = detail
                    )
                } finally {
                    connection.disconnect()
                }
            }
            BroadcastResult(success = false, endpoint = targetUrl, errorMessage = "Too many redirects")
        }.getOrElse { err ->
            BroadcastResult(
                success = false,
                endpoint = normalized,
                errorMessage = err.message?.takeIf { it.isNotBlank() } ?: err.javaClass.simpleName
            )
        }
    }

    /**
     * Asynchronously fires a broadcast packet on Dispatchers.IO and checks for new spectator cheers.
     */
    fun broadcastAsync(
        scope: CoroutineScope,
        spectatorEndpoint: String,
        packet: SpectatorPacket,
        bypassThrottle: Boolean = false,
        onCheerReceived: (String) -> Unit = {},
        onDiagnostics: (BroadcastResult) -> Unit = {},
        onResult: (Boolean) -> Unit = {}
    ) {
        val normalized = normalizeEndpointUrl(spectatorEndpoint)
        if (normalized.isEmpty()) {
            val invalid = BroadcastResult(success = false, errorMessage = "Invalid spectator URL")
            onResult(false)
            onDiagnostics(invalid)
            return
        }
        if (!bypassThrottle && !isBroadcastInFlight.compareAndSet(false, true)) {
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val result = broadcastWithDiagnostics(
                    spectatorEndpoint = normalized,
                    packet = packet,
                    bypassThrottle = bypassThrottle
                )
                onResult(result.success)
                onDiagnostics(result)
                if (result.success) {
                    val cheerUrl = resolveCheerEndpoint(result.endpoint.ifBlank { normalized })
                    if (cheerUrl != null) {
                        fetchNewestUnseenCheer(cheerUrl)?.let(onCheerReceived)
                    }
                }
            } finally {
                if (!bypassThrottle) {
                    isBroadcastInFlight.set(false)
                }
            }
        }
    }

    private fun fetchNewestUnseenCheer(cheerEndpoint: String): String? {
        return runCatching {
            val conn = (URL(cheerEndpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val (id, formatted) = parseLatestCheer(body) ?: return null
                    if (id != lastSeenCheerId) {
                        lastSeenCheerId = id
                        formatted
                    } else null
                } else null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
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
