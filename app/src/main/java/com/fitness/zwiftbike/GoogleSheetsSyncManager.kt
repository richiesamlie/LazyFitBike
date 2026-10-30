package com.fitness.zwiftbike

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages athlete telemetry and Coggan cycling metrics synchronization with Google Sheets.
 * Generates structured RFC 4180 CSV / TSV formats for 1-tap sheet imports and cloud tracking.
 */
object GoogleSheetsSyncManager {

    val CSV_HEADER = "Timestamp,Date,Route Name,Duration (s),Duration (min),Distance (km),Avg Power (W),Avg W/kg,Max Power (W),Normalized Power (W),Intensity Factor,TSS,Avg Cadence (RPM),Avg HR (BPM),Calories (kcal),Stars Earned,Compliance (%)"

    private fun buildMetricsList(
        summary: WorkoutSummary,
        routeName: String,
        riderWeightKg: Float,
        distanceKm: Float,
        caloriesKcal: Int,
        avgRpm: Int,
        avgBpm: Int
    ): List<String> {
        val now = System.currentTimeMillis()
        val sdfDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val dateStr = sdfDate.format(Date(now))
        val durationMins = String.format(Locale.US, "%.1f", summary.totalTimeSeconds / 60.0)
        val wPerKg = String.format(Locale.US, "%.2f", summary.avgWatts / riderWeightKg.coerceAtLeast(40f))

        return listOf(
            now.toString(),
            dateStr,
            routeName,
            summary.totalTimeSeconds.toString(),
            durationMins,
            String.format(Locale.US, "%.2f", distanceKm),
            summary.avgWatts.toString(),
            wPerKg,
            summary.maxWatts.toString(),
            summary.normalizedPowerWatts.toString(),
            String.format(Locale.US, "%.2f", summary.intensityFactor),
            summary.tss.toString(),
            avgRpm.toString(),
            avgBpm.toString(),
            caloriesKcal.toString(),
            summary.totalStarsEarned.toString(),
            String.format(Locale.US, "%.1f", summary.overallCompliancePercent)
        )
    }

    /**
     * Formats a completed [WorkoutSummary] into an RFC 4180 compliant CSV line.
     */
    fun buildCsvRow(
        summary: WorkoutSummary,
        routeName: String,
        riderWeightKg: Float,
        distanceKm: Float = 0f,
        caloriesKcal: Int = 0,
        avgRpm: Int = 0,
        avgBpm: Int = 0
    ): String {
        return buildMetricsList(summary, routeName, riderWeightKg, distanceKm, caloriesKcal, avgRpm, avgBpm)
            .mapIndexed { index, raw ->
                if (index == 1 || index == 2) {
                    "\"${raw.replace("\"", "\"\"")}\""
                } else if (raw.contains(",") || raw.contains("\"") || raw.contains("\n")) {
                    "\"${raw.replace("\"", "\"\"")}\""
                } else {
                    raw
                }
            }
            .joinToString(",")
    }

    /**
     * Formats into a Tab-Separated Values (TSV) clipboard string for instant paste
     * into Google Sheets. Immune to route names containing commas.
     */
    fun buildTsvClipboardSnippet(
        summary: WorkoutSummary,
        routeName: String,
        riderWeightKg: Float,
        distanceKm: Float = 0f,
        caloriesKcal: Int = 0,
        avgRpm: Int = 0,
        avgBpm: Int = 0
    ): String {
        return buildMetricsList(summary, routeName, riderWeightKg, distanceKm, caloriesKcal, avgRpm, avgBpm)
            .joinToString("\t") { raw ->
                raw.replace("\t", " ").replace("\n", " ")
            }
    }

    /**
     * Saves session telemetry to a local `.csv` file and presents the Android Share Sheet,
     * allowing 1-tap open in Google Sheets, Google Drive, or Gmail.
     */
    fun exportAndShareGoogleSheetsCsv(
        context: Context,
        summary: WorkoutSummary,
        routeName: String,
        riderWeightKg: Float,
        distanceKm: Float = 0f,
        caloriesKcal: Int = 0,
        avgRpm: Int = 0,
        avgBpm: Int = 0
    ): File? {
        return runCatching {
            val dir = File(context.cacheDir, "sheets").apply { mkdirs() }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "LazyFitBike_${timeStamp}.csv")

            val fullCsvContent = "$CSV_HEADER\n${buildCsvRow(summary, routeName, riderWeightKg, distanceKm, caloriesKcal, avgRpm, avgBpm)}\n"
            file.writeText(fullCsvContent, Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "LazyFitBike Workout: $routeName")
                putExtra(Intent.EXTRA_TEXT, "Cycling telemetry log ready for Google Sheets import.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(sendIntent, "Open in Google Sheets / Drive").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            file
        }.getOrNull()
    }
}
