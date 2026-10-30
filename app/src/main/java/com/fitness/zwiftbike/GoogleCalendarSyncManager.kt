package com.fitness.zwiftbike

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Manages scheduling structured cycling workouts directly into Google Calendar.
 * Builds formatted event details with step-by-step interval targets and Coggan power zones.
 */
object GoogleCalendarSyncManager {

    /**
     * Formats a clean, high-readability workout summary suitable for calendar event descriptions.
     */
    fun formatWorkoutDescription(workout: Workout, ftpWatts: Int): String {
        val sb = StringBuilder()
        sb.append("🚴 LazyFitBike Structured Training Session\n")
        sb.append("Workout: ${workout.name}\n")
        sb.append("Category: ${workout.category.name}\n")
        sb.append("Total Duration: ${workout.totalDurationSeconds / 60} minutes (${workout.steps.size} steps)\n")
        sb.append("Rider Target FTP: ${ftpWatts} W\n\n")
        sb.append("📋 Interval Structure:\n")

        for (step in workout.steps) {
            val mins = step.durationSeconds / 60
            val secs = step.durationSeconds % 60
            val timeStr = if (secs == 0) "${mins}m" else "${mins}m ${secs}s"
            val targetWatts = (step.targetPowerFraction * ftpWatts).roundToInt()
            val rpmStr = step.targetCadenceRpm?.let { " @ ${it} RPM" } ?: ""

            sb.append(String.format(
                Locale.US,
                "• %s: %s @ %dW (%d%% FTP, Zone %d)%s\n",
                step.name,
                timeStr,
                targetWatts,
                (step.targetPowerFraction * 100).roundToInt(),
                step.zone,
                rpmStr
            ))
        }

        sb.append("\nTip: Connect Samsung Galaxy Watch 4 in Ankle mode for live cadence or Wrist mode for optical heart rate power.")
        return sb.toString()
    }

    /**
     * Builds an Android [Intent.ACTION_INSERT] intent to schedule the workout in Google Calendar.
     * Defaults to starting 1 hour from now unless specified.
     */
    fun createCalendarInsertIntent(
        workout: Workout,
        ftpWatts: Int,
        startEpochMs: Long = System.currentTimeMillis() + 3600_000L
    ): Intent {
        val endEpochMs = startEpochMs + (workout.totalDurationSeconds * 1000L)
        val description = formatWorkoutDescription(workout, ftpWatts)

        return Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, "🚴 Ride: ${workout.name}")
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            putExtra(CalendarContract.Events.EVENT_LOCATION, "LazyFitBike Virtual Cockpit")
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startEpochMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endEpochMs)
            putExtra(CalendarContract.Events.ACCESS_LEVEL, CalendarContract.Events.ACCESS_PRIVATE)
            putExtra(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Launches the calendar scheduler for a workout.
     */
    fun launchCalendarScheduler(
        context: Context,
        workout: Workout,
        ftpWatts: Int,
        startEpochMs: Long = System.currentTimeMillis() + 3600_000L
    ): Boolean {
        return runCatching {
            val intent = createCalendarInsertIntent(workout, ftpWatts, startEpochMs)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
