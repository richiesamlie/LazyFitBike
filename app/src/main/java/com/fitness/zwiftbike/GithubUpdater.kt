package com.fitness.zwiftbike

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
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
 * Checks GitHub Releases on richiesamlie/LazyFitBike, downloads Phone/Watch APKs,
 * triggers Android system installation, and relays Watch APK updates over Bluetooth ChannelClient.
 */
object GithubUpdater {

    const val GITHUB_REPO = "richiesamlie/LazyFitBike"
    const val WATCH_APK_CHANNEL_PATH = "/velopulse/watch_apk_update"
    const val LOCAL_HTTP_PORT = 8765

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var httpServerJob: Job? = null
    private var activeServerSocket: ServerSocket? = null

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
                setRequestProperty("User-Agent", "LazyFitBike-Updater/1.1")
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
                    setRequestProperty("User-Agent", "LazyFitBike-Updater/1.1")
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
        val chooser = Intent.createChooser(shareIntent, "Send Watch APK (Wear Installer / Bugjaeger / Files)").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return "Opened Share Sheet for ${watchApkFile.name}."
    }

    private fun resolvePhoneLanIp(): String {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
            "127.0.0.1"
        }.getOrDefault("127.0.0.1")
    }

    fun startLocalWatchApkServer(watchApkFile: File): String {
        val ip = resolvePhoneLanIp()
        val baseUrl = "http://$ip:$LOCAL_HTTP_PORT"

        runCatching { activeServerSocket?.close() }
        httpServerJob?.cancel()

        httpServerJob = serverScope.launch {
            runCatching {
                val server = ServerSocket(LOCAL_HTTP_PORT)
                activeServerSocket = server
                while (isActive && !server.isClosed) {
                    val client = server.accept()
                    launch {
                        runCatching {
                            client.use { sock ->
                                val reqLine = sock.getInputStream().bufferedReader().readLine().orEmpty()
                                val out = BufferedOutputStream(sock.getOutputStream())
                                if (reqLine.contains("/watch.apk")) {
                                    val len = watchApkFile.length()
                                    val headers = buildString {
                                        append("HTTP/1.1 200 OK\r\n")
                                        append("Content-Type: application/vnd.android.package-archive\r\n")
                                        append("Content-Disposition: attachment; filename=\"LazyFitBike-watch.apk\"\r\n")
                                        append("Content-Length: $len\r\n")
                                        append("Connection: close\r\n\r\n")
                                    }
                                    out.write(headers.toByteArray(Charsets.UTF_8))
                                    watchApkFile.inputStream().use { fileIn ->
                                        fileIn.copyTo(out, 8192)
                                    }
                                    out.flush()
                                } else {
                                    val html = """
                                        <!DOCTYPE html>
                                        <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                                        <title>LazyFitBike Watch Installer</title></head>
                                        <body style="background:#09090B;color:#F4F4F5;font-family:sans-serif;text-align:center;padding:18px;">
                                        <h3 style="color:#F97316;margin-bottom:8px;">LazyFitBike Watch</h3>
                                        <p style="font-size:12px;color:#A1A1AA;">Galaxy Watch 4 Sensor APK</p>
                                        <a href="/watch.apk" style="display:inline-block;margin-top:12px;padding:14px 20px;background:#F97316;color:#FFF;text-decoration:none;border-radius:24px;font-weight:bold;font-size:14px;">INSTALL WATCH APK</a>
                                        </body></html>
                                    """.trimIndent()
                                    val bodyBytes = html.toByteArray(Charsets.UTF_8)
                                    val headers = buildString {
                                        append("HTTP/1.1 200 OK\r\n")
                                        append("Content-Type: text/html; charset=utf-8\r\n")
                                        append("Content-Length: ${bodyBytes.size}\r\n")
                                        append("Connection: close\r\n\r\n")
                                    }
                                    out.write(headers.toByteArray(Charsets.UTF_8))
                                    out.write(bodyBytes)
                                    out.flush()
                                }
                            }
                        }
                    }
                }
            }
        }
        return baseUrl
    }

    suspend fun pushWatchApkOverBluetooth(
        context: Context,
        watchApkFile: File
    ): String = withContext(Dispatchers.IO) {
        val localServerUrl = startLocalWatchApkServer(watchApkFile)
        runCatching {
            val appContext = context.applicationContext
            val nodes = withTimeoutOrNull(4000L) {
                Wearable.getNodeClient(appContext).connectedNodes.await()
            }.orEmpty()

            if (nodes.isEmpty()) {
                return@withContext "No Bluetooth watch node found. Local Wi-Fi server active at $localServerUrl (open in Watch Browser or tap Share Watch APK)."
            }

            val channelClient = Wearable.getChannelClient(appContext)
            var deliveredCount = 0
            for (node in nodes) {
                val channel = withTimeoutOrNull(6000L) {
                    channelClient.openChannel(node.id, WATCH_APK_CHANNEL_PATH).await()
                }
                if (channel != null) {
                    withTimeoutOrNull(25000L) {
                        channelClient.sendFile(channel, Uri.fromFile(watchApkFile)).await()
                    }
                    deliveredCount++
                }
            }

            if (deliveredCount > 0) {
                "Sent ${watchApkFile.name} over Bluetooth to $deliveredCount Watch(es)! Check your watch screen to confirm install."
            } else {
                "First-time Watch install (BT code 15: Watch app not installed yet). Local Wi-Fi Installer started at $localServerUrl - open that URL on Watch Browser or tap 'Share Watch APK' below!"
            }
        }.getOrElse { _ ->
            "First-time Watch install: Local Wi-Fi Installer ready at $localServerUrl - open in Watch Browser or tap 'Share Watch APK' below!"
        }
    }
}
