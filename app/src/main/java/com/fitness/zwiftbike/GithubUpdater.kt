package com.fitness.zwiftbike

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class GithubReleaseInfo(
    val tagName: String,
    val releaseName: String,
    val publishedAt: String,
    val phoneApkUrl: String?,
    val watchApkUrl: String?,
    val htmlUrl: String
)

/**
 * Checks GitHub Releases on richiesamlie/LazyFitBike, downloads minified Phone/Watch APKs,
 * triggers Android system installation, relays Watch APK updates over Bluetooth ChannelClient
 * (waking WearOtaListenerService on the watch), and provides Share Sheet export for ADB sideloaders.
 */
object GithubUpdater {

    const val GITHUB_REPO = "richiesamlie/LazyFitBike"
    const val WATCH_APK_CHANNEL_PATH = "/velopulse/watch_apk_update"

    private val tagRegex = Regex(""""tag_name"\s*:\s*"([^"]+)"""")
    private val nameRegex = Regex(""""name"\s*:\s*"([^"]+)"""")
    private val publishedRegex = Regex(""""published_at"\s*:\s*"([^"]+)"""")
    private val htmlUrlRegex = Regex(""""html_url"\s*:\s*"([^"]+)"""")
    private val downloadUrlRegex = Regex(""""browser_download_url"\s*:\s*"([^"]+\.apk)"""", RegexOption.IGNORE_CASE)

    fun parseReleaseJson(jsonText: String): GithubReleaseInfo? {
        val tag = tagRegex.find(jsonText)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
            ?: return null
        val releaseName = nameRegex.find(jsonText)?.groupValues?.getOrNull(1) ?: tag
        val publishedAt = publishedRegex.find(jsonText)?.groupValues?.getOrNull(1) ?: ""
        val htmlUrl = htmlUrlRegex.find(jsonText)?.groupValues?.getOrNull(1)
            ?: "https://github.com/$GITHUB_REPO/releases"

        var phoneUrl: String? = null
        var watchUrl: String? = null

        val allUrls = downloadUrlRegex.findAll(jsonText).map { it.groupValues[1] }.toList()
        val tagScopedUrls = allUrls.filter { it.contains("/$tag/") }.ifEmpty { allUrls }

        for (url in tagScopedUrls) {
            val lower = url.lowercase()
            if (lower.contains("watch") || lower.contains("wear")) {
                if (watchUrl == null) watchUrl = url
            } else if (lower.contains("phone") || lower.contains("app")) {
                if (phoneUrl == null) phoneUrl = url
            }
        }

        return GithubReleaseInfo(
            tagName = tag,
            releaseName = releaseName,
            publishedAt = publishedAt,
            phoneApkUrl = phoneUrl,
            watchApkUrl = watchUrl,
            htmlUrl = htmlUrl
        )
    }

    suspend fun fetchLatestRelease(): GithubReleaseInfo? = withContext(Dispatchers.IO) {
        val latestUrl = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
        val listUrl = "https://api.github.com/repos/$GITHUB_REPO/releases"

        fetchUrlText(latestUrl)?.let { parseReleaseJson(it) }
            ?: fetchUrlText(listUrl)?.let { parseReleaseJson(it) }
    }

    private fun fetchUrlText(urlStr: String): String? {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "LazyFitBike-Updater/1.3")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        }.getOrNull()
    }

    suspend fun downloadApkToFile(
        context: Context,
        apkUrl: String,
        targetFileName: String,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outFile = File(updatesDir, targetFileName)

            var currentUrl = apkUrl
            var redirects = 0
            var conn: HttpURLConnection
            while (true) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 12000
                    readTimeout = 15000
                    setRequestProperty("User-Agent", "LazyFitBike-Updater/1.3")
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

            if (conn.responseCode !in 200..299) return@withContext null
            val totalBytes = conn.contentLengthLong
            var downloadedBytes = 0L
            var lastReportedPct = -1

            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0L) {
                            val pct = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    output.flush()
                }
            }
            onProgress(100)
            outFile
        }.getOrNull()
    }

    fun promptInstallApk(context: Context, apkFile: File): String {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return "Downloaded APK file is empty or missing."
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
            return "Grant 'Install unknown apps' permission, then tap Install again."
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return "Launched Android Package Installer for ${apkFile.name}."
    }

    fun shareWatchApkFile(context: Context, watchApkFile: File): String {
        if (!watchApkFile.exists() || watchApkFile.length() == 0L) {
            return "Download Watch APK first before sharing."
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            watchApkFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, "LazyFitBike-watch.apk")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Send Watch APK (Bugjaeger / Wear Installer 2)").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return "Opened Share Sheet for ${watchApkFile.name}."
    }

    suspend fun pushWatchApkOverBluetooth(
        context: Context,
        watchApkFile: File
    ): String = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val nodes = withTimeoutOrNull(5000L) {
                Wearable.getNodeClient(appContext).connectedNodes.await()
            }.orEmpty()

            if (nodes.isEmpty()) {
                return@withContext "No Bluetooth watch node found. Ensure your Galaxy Watch 4 is connected via Galaxy Wearable, or tap the orange OTA pill directly on your watch over Wi-Fi."
            }

            val messageClient = Wearable.getMessageClient(appContext)
            val channelClient = Wearable.getChannelClient(appContext)
            var deliveredCount = 0

            for (node in nodes) {
                // Wake up WearOtaListenerService on the watch first
                runCatching {
                    withTimeoutOrNull(3000L) {
                        messageClient.sendMessage(node.id, "/velopulse/ping", "ota".toByteArray(Charsets.UTF_8)).await()
                    }
                }
                val channel = withTimeoutOrNull(8000L) {
                    channelClient.openChannel(node.id, WATCH_APK_CHANNEL_PATH).await()
                }
                if (channel != null) {
                    withTimeoutOrNull(30000L) {
                        channelClient.sendFile(channel, Uri.fromFile(watchApkFile)).await()
                    }
                    deliveredCount++
                }
            }

            if (deliveredCount > 0) {
                "Sent ${watchApkFile.name} (1.1 MB) over Bluetooth to $deliveredCount Watch(es)! Check your watch screen to confirm update."
            } else {
                "Watch did not accept Bluetooth channel. Open LazyFitBike on your watch and tap the orange OTA button (or tap 'Share Watch APK' below for ADB)."
            }
        }.getOrElse { err ->
            "Bluetooth relay error: ${err.message ?: "Unknown"}. Open LazyFitBike on your watch and tap the orange OTA button."
        }
    }
}
