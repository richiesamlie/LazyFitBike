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
 * whenever the phone opens a Bluetooth ChannelClient stream ("/lazyfitbike/watch_apk_update")
 * or sends control/ping messages ("/lazyfitbike/ping", "/lazyfitbike/control").
 */
class WearOtaListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        super.onMessageReceived(event)
        when (event.path) {
            "/lazyfitbike/ping", "/velopulse/ping" -> {
                val ver = WatchGithubUpdater.resolveInstalledVersion(this)
                val replyPayload = ver.toByteArray(Charsets.UTF_8)
                val client = Wearable.getMessageClient(this)
                client.sendMessage(event.sourceNodeId, "/lazyfitbike/pong", replyPayload)
                client.sendMessage(event.sourceNodeId, "/velopulse/pong", replyPayload)
            }
            "/lazyfitbike/control", "/velopulse/control" -> {
                val cmd = String(event.data, Charsets.UTF_8).trim()
                val intent = Intent(this, SensorWorkoutService::class.java).apply {
                    when {
                        cmd.equals("TOGGLE_MODE", ignoreCase = true) -> {
                            action = SensorWorkoutService.ACTION_TOGGLE_MODE
                        }
                        cmd.startsWith("SET_MODE:", ignoreCase = true) -> {
                            action = SensorWorkoutService.ACTION_SET_MODE
                            putExtra(
                                SensorWorkoutService.EXTRA_MODE,
                                cmd.substringAfter(":").trim().uppercase()
                            )
                        }
                        cmd.startsWith("SET_MAX_HR:", ignoreCase = true) -> {
                            action = SensorWorkoutService.ACTION_SET_MAX_HR
                            putExtra(
                                SensorWorkoutService.EXTRA_MAX_HR,
                                cmd.substringAfter(":").trim().toIntOrNull() ?: 190
                            )
                        }
                        cmd.startsWith("WORKOUT_STEP:", ignoreCase = true) -> {
                            action = SensorWorkoutService.ACTION_SET_WORKOUT_STEP
                            putExtra(
                                SensorWorkoutService.EXTRA_WORKOUT_PAYLOAD,
                                cmd.substringAfter(":")
                            )
                        }
                        cmd.equals("END_WORKOUT", ignoreCase = true) -> {
                            action = SensorWorkoutService.ACTION_CLEAR_WORKOUT
                        }
                        else -> return
                    }
                }
                runCatching { startService(intent) }
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        super.onChannelOpened(channel)
        if (channel.path == WatchGithubUpdater.WATCH_APK_CHANNEL_PATH ||
            channel.path == WatchGithubUpdater.LEGACY_WATCH_APK_CHANNEL_PATH
        ) {
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
        if (channel.path == WatchGithubUpdater.WATCH_APK_CHANNEL_PATH ||
            channel.path == WatchGithubUpdater.LEGACY_WATCH_APK_CHANNEL_PATH
        ) {
            val updatesDir = File(cacheDir, "updates")
            val readyFile = File(updatesDir, "LazyFitBike-watch-bt.apk")
            if (readyFile.exists() && readyFile.length() > 0L) {
                val status = WatchGithubUpdater.promptInstallWatchApk(this, readyFile)
                WatchGithubUpdater.statusFlow.value = status
            }
        }
    }
}
