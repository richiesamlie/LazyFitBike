package com.fitness.zwiftbike

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class RecordedTrackPoint(
    val lat: Double,
    val lon: Double,
    val eleMeters: Double,
    val timestampEpochMs: Long,
    val watts: Int,
    val rpm: Int,
    val bpm: Int
)

/**
 * Builds Strava-compatible GPX 1.1 files with Garmin TrackPointExtension v1
 * (Heart Rate BPM, Cadence RPM, Power Watts, GPS Lat/Lon, and Barometric Elevation)
 * and supports both Android Share Sheet export and direct Strava v3 API upload.
 */
object StravaSyncManager {

    private fun formatIsoUtc(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMs))
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    fun buildStravaGpxXml(
        routeName: String,
        trackPoints: List<RecordedTrackPoint>
    ): String {
        val safeTitle = escapeXml("LazyFitBike Ride: $routeName")
        val startTimeMs = trackPoints.firstOrNull()?.timestampEpochMs ?: System.currentTimeMillis()
        val sb = StringBuilder(4096)

        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"LazyFitBike Indoor Cycling with Barometer\"\n")
        sb.append("  xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        sb.append("  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        sb.append("  xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(safeTitle).append("</name>\n")
        sb.append("    <time>").append(formatIsoUtc(startTimeMs)).append("</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(safeTitle).append("</name>\n")
        sb.append("    <type>VirtualRide</type>\n")
        sb.append("    <trkseg>\n")

        for (pt in trackPoints) {
            sb.append(
                String.format(
                    Locale.US,
                    "      <trkpt lat=\"%.6f\" lon=\"%.6f\">\n",
                    pt.lat,
                    pt.lon
                )
            )
            sb.append(String.format(Locale.US, "        <ele>%.1f</ele>\n", pt.eleMeters))
            sb.append("        <time>").append(formatIsoUtc(pt.timestampEpochMs)).append("</time>\n")
            sb.append("        <extensions>\n")
            sb.append("          <power>").append(pt.watts).append("</power>\n")
            sb.append("          <gpxtpx:TrackPointExtension>\n")
            if (pt.bpm > 0) {
                sb.append("            <gpxtpx:hr>").append(pt.bpm).append("</gpxtpx:hr>\n")
            }
            sb.append("            <gpxtpx:cad>").append(pt.rpm).append("</gpxtpx:cad>\n")
            sb.append("          </gpxtpx:TrackPointExtension>\n")
            sb.append("        </extensions>\n")
            sb.append("      </trkpt>\n")
        }

        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /**
     * Writes the GPX 1.1 file to cache/rides and opens the Android Share Chooser
     * so the user can send it directly to Strava or save to Downloads.
     */
    fun exportAndShareGpx(
        context: Context,
        routeName: String,
        trackPoints: List<RecordedTrackPoint>
    ): File {
        val gpxXml = buildStravaGpxXml(routeName, trackPoints)
        val ridesDir = File(context.cacheDir, "rides").apply { mkdirs() }
        val fileName = "lazyfitbike_strava_${System.currentTimeMillis()}.gpx"
        val gpxFile = File(ridesDir, fileName)
        gpxFile.writeText(gpxXml, Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            gpxFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "LazyFitBike Ride: $routeName")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share GPX Ride to Strava").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return gpxFile
    }

    /**
     * Uploads the GPX ride directly to Strava API v3 (`POST https://www.strava.com/api/v3/uploads`)
     * using a user-supplied OAuth2 Bearer access token with `activity:write` scope.
     */
    suspend fun uploadToStravaApi(
        accessToken: String,
        routeName: String,
        trackPoints: List<RecordedTrackPoint>
    ): String = withContext(Dispatchers.IO) {
        val cleanToken = accessToken.trim()
        if (cleanToken.isEmpty()) {
            return@withContext "Enter a valid Strava OAuth2 Access Token (scope: activity:write) or use Export & Share .GPX."
        }
        if (trackPoints.isEmpty()) {
            return@withContext "No trackpoints recorded yet. Pedal for a few seconds first."
        }

        val gpxBytes = buildStravaGpxXml(routeName, trackPoints).toByteArray(Charsets.UTF_8)
        val boundary = "----LazyFitBikeStravaBoundary${System.currentTimeMillis()}"
        val lineEnd = "\r\n"

        runCatching {
            val url = URL("https://www.strava.com/api/v3/uploads")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                useCaches = false
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Authorization", "Bearer $cleanToken")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(conn.outputStream).use { out ->
                fun writeFormField(name: String, value: String) {
                    out.writeBytes("--$boundary$lineEnd")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"$lineEnd$lineEnd")
                    out.writeBytes("$value$lineEnd")
                }

                writeFormField("data_type", "gpx")
                writeFormField("activity_type", "ride")
                writeFormField("trainer", "0")
                writeFormField("name", "LazyFitBike: $routeName")
                writeFormField("description", "Static bike ride recorded via LazyFitBike + Samsung Galaxy Watch 4")

                out.writeBytes("--$boundary$lineEnd")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"lazyfitbike_ride.gpx\"$lineEnd")
                out.writeBytes("Content-Type: application/gpx+xml$lineEnd$lineEnd")
                out.write(gpxBytes)
                out.writeBytes(lineEnd)
                out.writeBytes("--$boundary--$lineEnd")
                out.flush()
            }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code in 200..299) {
                "Uploaded to Strava successfully (HTTP $code). Check your Strava feed!"
            } else {
                "Strava API returned HTTP $code: ${body.take(140)}"
            }
        }.getOrElse { err ->
            "Network error uploading to Strava: ${err.message ?: "Unknown error"}"
        }
    }
}
