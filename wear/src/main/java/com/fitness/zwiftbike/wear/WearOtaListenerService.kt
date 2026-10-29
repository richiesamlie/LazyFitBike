package com.fitness.zwiftbike.wear

import android.content.Intent
import android.net.Uri
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File

/**
 * Background WearableListenerService that wakes up the Galaxy Watch 4 app
 * whenever the phone opens a Bluetooth ChannelClient stream ("/velopulse/watch_apk_update")
 * or sends control/ping messages ("/velopulse/ping", "/velopulse/control").
 */
class WearOtaListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        super.onMessageReceived(event)
        when (event.path) {
            "/velopulse/ping" -> {
                val ver = WatchGithubUpdater.resolveInstalledVersion(this)
                Wearable.getMessageClient(this)
                    .sendMessage(event.sourceNodeId, "/velopulse/pong", ver.toByteArray(Charsets.UTF_8))
            }
            "/velopulse/control" -> {
                val cmd = String(event.data, Charsets.UTF_8).trim().uppercase()
                if (cmd == "TOGGLE_MODE") {
                    val intent = Intent(this, SensorWorkoutService::class.java).apply {
                        action = SensorWorkoutService.ACTION_TOGGLE_MODE
                    }
                    runCatching { startService(intent) }
                }
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        super.onChannelOpened(channel)
        if (channel.path == WatchGithubUpdater.WATCH_APK_CHANNEL_PATH) {
            val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
            val destFile = File(updatesDir, "LazyFitBike-watch-bt.apk")
            WatchGithubUpdater.statusFlow.value = "Receiving BT APK..."
            runCatching {
                val wakeIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(wakeIntent)
            }
            Wearable.getChannelClient(this)
                .receiveFile(channel, Uri.fromFile(destFile), false)
        }
    }

    override fun onInputClosed(
        channel: ChannelClient.Channel,
        closeReason: Int,
        appSpecificErrorCode: Int
    ) {
        super.onInputClosed(channel, closeReason, appSpecificErrorCode)
        if (channel.path == WatchGithubUpdater.WATCH_APK_CHANNEL_PATH) {
            val updatesDir = File(cacheDir, "updates")
            val readyFile = File(updatesDir, "LazyFitBike-watch-bt.apk")
            if (readyFile.exists() && readyFile.length() > 0L) {
                val status = WatchGithubUpdater.promptInstallWatchApk(this, readyFile)
                WatchGithubUpdater.statusFlow.value = status
            }
        }
    }
}
