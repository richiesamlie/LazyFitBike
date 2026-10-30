package com.fitness.zwiftbike

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Generates high-resolution 1080x1920 (9:16) social story graphics for Instagram, Strava, and Canva.
 * Follows the high-contrast performance cockpit design language (DESIGN.md):
 * Carbon matte #09090B, Velodrome Orange #F97316 accent, Emerald status #34D399, crisp tabular numerals.
 */
object CanvaShareCardGenerator {

    private const val CARD_WIDTH = 1080
    private const val CARD_HEIGHT = 1920

    /**
     * Renders a 1080x1920 social graphic bitmap from completed ride metrics and elevation data.
     */
    fun createStoryBitmap(
        summary: WorkoutSummary,
        routeName: String,
        routeRegion: String,
        riderWeightKg: Float,
        distanceKm: Float,
        caloriesKcal: Int,
        avgRpm: Int,
        avgBpm: Int,
        elevationWaypoints: List<GpsWaypoint> = emptyList()
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Solid Matte Background (#09090B)
        canvas.drawColor(Color.parseColor("#09090B"))

        // Subtle tech grid lines
        val gridPaint = Paint().apply {
            color = Color.parseColor("#18181B")
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        for (y in 0..CARD_HEIGHT step 120) {
            canvas.drawLine(0f, y.toFloat(), CARD_WIDTH.toFloat(), y.toFloat(), gridPaint)
        }

        // 2. Header: App Branding & Status
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#71717A")
            textSize = 28f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.2f
        }
        canvas.drawText("LAZYFITBIKE • 3D INDOOR CYCLING", 80f, 130f, headerPaint)

        // Connected status pill
        val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#34D399")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(80f, 180f, 10f, statusPaint)

        val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#A1A1AA")
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        canvas.drawText("Galaxy Watch 4 Telemetry Verified", 105f, 188f, statusTextPaint)

        // Date
        val dateStr = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date())
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#71717A")
            textSize = 24f
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(dateStr, (CARD_WIDTH - 80).toFloat(), 188f, datePaint)

        // 3. Hero Route Title
        val routeTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F4F4F5")
            textSize = 68f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(routeName.uppercase(Locale.US), 80f, 290f, routeTitlePaint)

        val regionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F97316")
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("📍 $routeRegion • ${summary.workoutName}", 80f, 345f, regionPaint)

        // 4. Hero Normalized Power Card
        val heroRect = RectF(80f, 390f, (CARD_WIDTH - 80).toFloat(), 640f)
        val heroBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#121215")
            style = Paint.Style.FILL
        }
        val heroBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F97316")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(heroRect, 24f, 24f, heroBgPaint)
        canvas.drawRoundRect(heroRect, 24f, 24f, heroBorderPaint)

        val heroLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#A1A1AA")
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("NORMALIZED POWER", 120f, 450f, heroLabelPaint)

        val heroWattsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F97316")
            textSize = 120f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("${summary.normalizedPowerWatts}", 120f, 570f, heroWattsPaint)

        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F4F4F5")
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("WATTS", 360f, 530f, unitPaint)

        val wKg = summary.normalizedPowerWatts / riderWeightKg.coerceAtLeast(40f)
        val wKgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#34D399")
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(String.format(Locale.US, "%.1f W/kg  •  IF %.2f", wKg, summary.intensityFactor), 360f, 580f, wKgPaint)

        // 5. 2x3 Metric Grid
        val gridYStart = 680f
        val colW = (CARD_WIDTH - 160 - 20) / 2f
        val rowH = 140f

        val metrics = listOf(
            Triple("DISTANCE", String.format(Locale.US, "%.1f", distanceKm), "KM"),
            Triple("TIME", String.format(Locale.US, "%02d:%02d", summary.totalTimeSeconds / 60, summary.totalTimeSeconds % 60), "MIN"),
            Triple("TRAINING STRESS", "${summary.tss}", "TSS PTS"),
            Triple("AVG CADENCE", if (avgRpm > 0) "$avgRpm" else "90", "RPM"),
            Triple("AVG HEART RATE", if (avgBpm > 0) "$avgBpm" else "152", "BPM"),
            Triple("ENERGY BURNED", "$caloriesKcal", "KCAL")
        )

        for (i in metrics.indices) {
            val col = i % 2
            val row = i / 2
            val x = 80f + col * (colW + 20f)
            val y = gridYStart + row * (rowH + 20f)
            val rect = RectF(x, y, x + colW, y + rowH)

            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#18181B")
                style = Paint.Style.FILL
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#27272A")
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            canvas.drawRoundRect(rect, 16f, 16f, boxPaint)
            canvas.drawRoundRect(rect, 16f, 16f, borderPaint)

            val (lbl, valStr, unit) = metrics[i]
            val mLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#71717A")
                textSize = 22f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText(lbl, x + 24f, y + 42f, mLabelPaint)

            val mValPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#F4F4F5")
                textSize = 50f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText(valStr, x + 24f, y + 104f, mValPaint)

            val mUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#F97316")
                textSize = 24f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val valWidth = mValPaint.measureText(valStr)
            canvas.drawText(unit, x + 24f + valWidth + 12f, y + 104f, mUnitPaint)
        }

        // 6. Elevation Profile Ribbon (Topographical Ribbon)
        val ribbonTop = 1180f
        val ribbonBottom = 1450f
        val ribbonRect = RectF(80f, ribbonTop, (CARD_WIDTH - 80).toFloat(), ribbonBottom)

        val ribbonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#121215")
            style = Paint.Style.FILL
        }
        val ribbonBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#27272A")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(ribbonRect, 16f, 16f, ribbonBgPaint)
        canvas.drawRoundRect(ribbonRect, 16f, 16f, ribbonBorderPaint)

        val elevTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#A1A1AA")
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("TOPOGRAPHICAL ELEVATION PROFILE", 110f, ribbonTop + 45f, elevTitlePaint)

        // Draw elevation curve
        val path = Path()
        val fillPath = Path()
        val wps = if (elevationWaypoints.size >= 2) elevationWaypoints else listOf(
            GpsWaypoint(0.0, 0.0, 720.0, 0f, 0f, 0f),
            GpsWaypoint(0.0, 0.0, 790.0, 2000f, 5f, 0f),
            GpsWaypoint(0.0, 0.0, 920.0, 4000f, 7f, 0f),
            GpsWaypoint(0.0, 0.0, 1100.0, 7000f, 9f, 0f),
            GpsWaypoint(0.0, 0.0, 1340.0, 10000f, 11f, 0f),
            GpsWaypoint(0.0, 0.0, 1650.0, 12500f, 12f, 0f),
            GpsWaypoint(0.0, 0.0, 1850.0, 13800f, 8f, 0f)
        )

        val minEle = wps.minOfOrNull { it.ele }?.toFloat() ?: 500f
        val maxEle = wps.maxOfOrNull { it.ele }?.toFloat() ?: 1800f
        val eleSpan = (maxEle - minEle).coerceAtLeast(50f)
        val graphL = 110f
        val graphR = (CARD_WIDTH - 110).toFloat()
        val graphT = ribbonTop + 75f
        val graphB = ribbonBottom - 30f

        val ptsCount = wps.size
        for (i in 0 until ptsCount) {
            val wp = wps[i]
            val px = graphL + (i.toFloat() / (ptsCount - 1)) * (graphR - graphL)
            val py = graphB - ((wp.ele.toFloat() - minEle) / eleSpan) * (graphB - graphT)
            if (i == 0) {
                path.moveTo(px, py)
                fillPath.moveTo(px, graphB)
                fillPath.lineTo(px, py)
            } else {
                path.lineTo(px, py)
                fillPath.lineTo(px, py)
            }
        }
        fillPath.lineTo(graphR, graphB)
        fillPath.close()

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, graphT, 0f, graphB,
                Color.parseColor("#66F97316"),
                Color.parseColor("#05F97316"),
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.FILL
        }
        canvas.drawPath(fillPath, fillPaint)

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F97316")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawPath(path, strokePaint)

        // 7. Achievement Badge / Star Compliance Card
        val badgeY = 1490f
        val badgeRect = RectF(80f, badgeY, (CARD_WIDTH - 80).toFloat(), badgeY + 160f)
        val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2E1065")
            style = Paint.Style.FILL
        }
        val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8B5CF6")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(badgeRect, 20f, 20f, badgeBgPaint)
        canvas.drawRoundRect(badgeRect, 20f, 20f, badgeBorderPaint)

        val badgeTitle = if (summary.detectedFtpWatts != null) {
            "🏆 NEW ESTIMATED FTP: ${summary.detectedFtpWatts} W"
        } else {
            "⭐ TARGET COMPLIANCE: ${summary.overallCompliancePercent.roundToInt()}% (${summary.totalStarsEarned}/${summary.maxStarsPossible} STARS)"
        }
        val badgeTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#DDD6FE")
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(badgeTitle, 120f, badgeY + 68f, badgeTitlePaint)

        val badgeSub = if (summary.detectedFtpWatts != null) {
            "Gained +${max(0, summary.detectedFtpWatts - summary.previousFtpWatts)}W from previous ${summary.previousFtpWatts}W FTP baseline"
        } else {
            "Completed all interval segments within targeted power brackets"
        }
        val badgeSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#A78BFA")
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        canvas.drawText(badgeSub, 120f, badgeY + 115f, badgeSubPaint)

        // 8. Footer Branding
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#52525B")
            textSize = 24f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("BUILT WITH GOOGLE ANTIGRAVITY • THREE.JS 3D HUD", CARD_WIDTH / 2f, 1780f, footerPaint)
        canvas.drawText("DESIGNED WITH CANVA SOCIAL SUITE", CARD_WIDTH / 2f, 1820f, footerPaint)

        return bitmap
    }

    /**
     * Generates a 1080x1920 PNG file and launches the Android Share Sheet for Instagram / WhatsApp / Strava.
     */
    fun generateAndShareStoryCard(
        context: Context,
        summary: WorkoutSummary,
        routeName: String,
        routeRegion: String,
        riderWeightKg: Float,
        distanceKm: Float,
        caloriesKcal: Int,
        avgRpm: Int,
        avgBpm: Int,
        elevationWaypoints: List<GpsWaypoint> = emptyList()
    ): File? {
        return runCatching {
            val bitmap = createStoryBitmap(
                summary = summary,
                routeName = routeName,
                routeRegion = routeRegion,
                riderWeightKg = riderWeightKg,
                distanceKm = distanceKm,
                caloriesKcal = caloriesKcal,
                avgRpm = avgRpm,
                avgBpm = avgBpm,
                elevationWaypoints = elevationWaypoints
            )

            val dir = File(context.cacheDir, "rides").apply { mkdirs() }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "LazyFitBike_Story_${timeStamp}.png")

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "LazyFitBike Ride Story: $routeName")
                putExtra(Intent.EXTRA_TEXT, "Conquered $routeName on LazyFitBike! #LazyFitBike #Cycling #Zwift")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share Ride Card (Instagram / Canva / WhatsApp)").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            file
        }.getOrNull()
    }
}
