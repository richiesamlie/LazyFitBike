package com.fitness.zwiftbike

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Google Drive Cloud Vault Sync Manager.
 * Facilitates 1-tap backup of exported GPS ride tracks (.gpx), Google Sheets summaries (.csv),
 * and structured workouts (.zwo) directly to Google Drive ("Save to Drive").
 */
object GoogleDriveSyncManager {

    const val GOOGLE_DRIVE_PACKAGE = "com.google.android.apps.docs"

    /**
     * Builds a clean standard filename for Google Drive cloud vault storage.
     */
    fun buildBackupFileName(prefix: String, routeOrWorkoutName: String, extension: String): String {
        val cleanName = routeOrWorkoutName
            .replace("[^a-zA-Z0-9_\\-]".toRegex(), "_")
            .trim('_')
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ext = extension.removePrefix(".")
        return "LazyFitBike_${prefix}_${cleanName}_${timeStamp}.${ext}"
    }

    /**
     * Builds an Android Intent targeting Google Drive ("Save to Drive") for an exported file.
     */
    fun createGoogleDriveUploadIntent(context: Context, file: File, mimeType: String): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.name)
            putExtra(Intent.EXTRA_SUBJECT, "LazyFitBike Cloud Backup: ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Test if Google Drive app is installed on user device
        val pm = context.packageManager
        val isDriveInstalled = runCatching {
            pm.getPackageInfo(GOOGLE_DRIVE_PACKAGE, 0)
            true
        }.getOrDefault(false)

        if (isDriveInstalled) {
            intent.setPackage(GOOGLE_DRIVE_PACKAGE)
        }

        return intent
    }

    /**
     * Launches the Google Drive cloud vault backup flow.
     */
    fun launchGoogleDriveBackup(context: Context, file: File, mimeType: String = "application/gpx+xml"): Boolean {
        return runCatching {
            val intent = createGoogleDriveUploadIntent(context, file, mimeType)
            val chooser = Intent.createChooser(intent, "Backup to Google Drive Vault").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }
}
