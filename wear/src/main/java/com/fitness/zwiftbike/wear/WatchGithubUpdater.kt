package com.fitness.zwiftbike.wear

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * BroadcastReceiver handling PackageInstaller session callbacks on Wear OS.
 * Launches the system confirmation dialog when STATUS_PENDING_USER_ACTION is emitted.
 */
class WatchInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirmIntent) }
                    WatchGithubUpdater.statusFlow.value = "Confirm on Watch"
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val ver = WatchGithubUpdater.resolveInstalledVersion(context)
                WatchGithubUpdater.statusFlow.value = "Updated ($ver)"
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                if (!msg.isNullOrBlank()) {
                    WatchGithubUpdater.statusFlow.value = "Install err ($status)"
                }
            }
        }
    }
}

/**
 * Direct GitHub OTA & Bluetooth PackageInstaller updater for Samsung Galaxy Watch 4 (:wear).
 */
object WatchGithubUpdater {

    const val GITHUB_REPO = "richiesamlie/LazyFitBike"
    const val WATCH_APK_CHANNEL_PATH = "/velopulse/watch_apk_update"

    val statusFlow = MutableStateFlow("OTA Update")

    private val tagRegex = Regex(""""tag_name"\s*:\s*"([^"]+)"""")
    private val downloadUrlRegex = Regex(""""browser_download_url"\s*:\s*"([^"]+\.apk)"""", RegexOption.IGNORE_CASE)

    fun resolveInstalledVersion(context: Context): String {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val vName = info.versionName ?: "1.3.1"
            if (vName.startsWith("v")) vName else "v$vName"
        }.getOrDefault("v1.3.1")
    }

    suspend fun downloadLatestWatchApkFromGithub(
        context: Context,
        onStatus: (String) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            onStatus("Checking GitHub...")
            val releaseJson = fetchUrlText("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
                ?: fetchUrlText("https://api.github.com/repos/$GITHUB_REPO/releases")
                ?: run {
                    onStatus("No GitHub release")
                    return@withContext null
                }

            val tag = tagRegex.find(releaseJson)?.groupValues?.getOrNull(1) ?: "latest"
            val installedVer = resolveInstalledVersion(context)
            val allUrls = downloadUrlRegex.findAll(releaseJson).map { it.groupValues[1] }.toList()
            val tagScopedUrls = allUrls.filter { it.contains("/$tag/") }.ifEmpty { allUrls }
            var watchApkUrl: String? = null
            for (url in tagScopedUrls) {
                val lower = url.lowercase()
                if (lower.contains("watch") || lower.contains("wear")) {
                    watchApkUrl = url
                    break
                }
            }

            if (watchApkUrl.isNullOrEmpty()) {
                onStatus("$tag: No watch APK")
                return@withContext null
            }

            if (installedVer.removePrefix("v").equals(tag.removePrefix("v"), ignoreCase = true) &&
                statusFlow.value != "Up to Date ($installedVer)"
            ) {
                onStatus("Up to Date ($installedVer)")
                return@withContext null
            }

            onStatus("DL $tag (0%)")
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outFile = File(updatesDir, "LazyFitBike-watch.apk")

            var currentUrl = watchApkUrl
            var redirects = 0
            var conn: HttpURLConnection
            while (true) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 12000
                    readTimeout = 15000
                    setRequestProperty("User-Agent", "LazyFitBike-WatchUpdater/1.3")
                }
                val code = conn.responseCode
                if (code in 300..399 && redirects < 5) {
                    val loc = conn.getHeaderField("Location") ?: break
                    currentUrl = loc
                    redirects++
                } else {
                    break
                }
            }

            if (conn.responseCode !in 200..299) {
                onStatus("HTTP ${conn.responseCode}")
                return@withContext null
            }

            val total = conn.contentLengthLong
            var downloaded = 0L
            var lastReportedPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0L) {
                            val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                onStatus("DL $tag ($pct%)")
                            }
                        }
                    }
                    output.flush()
                }
            }
            onStatus("Ready: $tag")
            outFile
        }.getOrElse {
            onStatus("Offline / Use Phone BT")
            null
        }
    }

    private fun fetchUrlText(urlStr: String): String? {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "LazyFitBike-WatchUpdater/1.3")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        }.getOrNull()
    }

    fun promptInstallWatchApk(context: Context, apkFile: File): String {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return "Watch APK missing"
        }
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            val settingsOpened = runCatching {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
            }.isSuccess
            if (settingsOpened) {
                return "Allow Unknown Apps"
            }
        }

        // Primary Method: Android PackageInstaller.Session API (native Wear OS self-updater)
        val sessionCommitted = runCatching {
            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("LazyFitBike-watch.apk", 0, apkFile.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val callbackIntent = Intent(context, WatchInstallReceiver::class.java)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(context, sessionId, callbackIntent, flags)
                session.commit(pendingIntent.intentSender)
            }
        }.isSuccess

        // Fallback Method: Standard ACTION_VIEW package archive intent via FileProvider
        if (!sessionCommitted) {
            runCatching {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.wear.fileprovider",
                    apkFile
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            }
        }
        return "Installing Update..."
    }
}
