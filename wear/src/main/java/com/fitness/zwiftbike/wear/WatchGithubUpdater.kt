package com.fitness.zwiftbike.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Direct GitHub OTA updater for Samsung Galaxy Watch 4 (:wear).
 * Downloads LazyFitBike-watch.apk from richiesamlie/LazyFitBike Releases and launches the Wear OS installer.
 */
object WatchGithubUpdater {

    const val GITHUB_REPO = "richiesamlie/LazyFitBike"
    const val WATCH_APK_CHANNEL_PATH = "/velopulse/watch_apk_update"

    private val tagRegex = Regex(""""tag_name"\s*:\s*"([^"]+)"""")
    private val downloadUrlRegex = Regex(""""browser_download_url"\s*:\s*"([^"]+\.apk)"""", RegexOption.IGNORE_CASE)

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
                    setRequestProperty("User-Agent", "LazyFitBike-WatchUpdater/1.1")
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
            onStatus("Offline / Use Phone")
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
                setRequestProperty("User-Agent", "LazyFitBike-WatchUpdater/1.1")
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
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return "Allow Install Unknown"
        }

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
        return "Installing Update..."
    }
}
