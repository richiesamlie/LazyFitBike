package com.fitness.zwiftbike.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * Samsung Galaxy Watch 4 circular UI with inscribed safe octagon layout,
 * direct GitHub OTA updater, and Bluetooth ChannelClient APK relay receiver.
 */
class MainActivity : ComponentActivity() {

    private var incomingWatchApkFile: File? = null

    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            if (channel.path == WatchGithubUpdater.WATCH_APK_CHANNEL_PATH) {
                val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
                val destFile = File(updatesDir, "LazyFitBike-watch-bt.apk")
                incomingWatchApkFile = destFile
                WatchGithubUpdater.statusFlow.value = "Receiving BT APK..."
                Wearable.getChannelClient(this@MainActivity)
                    .receiveFile(channel, Uri.fromFile(destFile), false)
            }
        }

        override fun onInputClosed(
            channel: ChannelClient.Channel,
            closeReason: Int,
            appSpecificErrorCode: Int
        ) {
            if (channel.path == WatchGithubUpdater.WATCH_APK_CHANNEL_PATH) {
                val readyFile = incomingWatchApkFile
                if (readyFile != null && readyFile.exists() && readyFile.length() > 0L) {
                    WatchGithubUpdater.statusFlow.value = "BT APK Ready: Install"
                    val msg = WatchGithubUpdater.promptInstallWatchApk(this@MainActivity, readyFile)
                    WatchGithubUpdater.statusFlow.value = msg
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (HeartRateTrackerManager.hasHeartRatePermission(this)) {
            sendServiceAction(SensorWorkoutService.ACTION_REFRESH_SENSORS)
        }
    }

    private fun checkAndRequestPermissions() {
        val missing = HeartRateTrackerManager.requiredRuntimePermissions().filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        checkAndRequestPermissions()

        runCatching {
            Wearable.getChannelClient(this).registerChannelCallback(channelCallback)
        }
        if (WatchGithubUpdater.statusFlow.value == "OTA Update") {
            WatchGithubUpdater.statusFlow.value = "${WatchGithubUpdater.resolveInstalledVersion(this)} OTA"
        }

        setContent {
            val workoutState by SensorWorkoutService.state.collectAsState()
            val updateStatus by WatchGithubUpdater.statusFlow.collectAsState()
            WatchSensorScreen(
                state = workoutState,
                updateStatus = updateStatus,
                onToggleMode = { sendServiceAction(SensorWorkoutService.ACTION_TOGGLE_MODE) },
                onToggleActive = {
                    if (!workoutState.isActive && !HeartRateTrackerManager.hasHeartRatePermission(this)) {
                        checkAndRequestPermissions()
                    }
                    val action = if (workoutState.isActive) {
                        SensorWorkoutService.ACTION_STOP
                    } else {
                        SensorWorkoutService.ACTION_START
                    }
                    sendServiceAction(action)
                },
                onToggleSim = { sendServiceAction(SensorWorkoutService.ACTION_TOGGLE_SIM) },
                onRequestPermissions = { checkAndRequestPermissions() },
                onTriggerGithubUpdate = { triggerWatchOtaUpdate() }
            )
        }
    }

    private fun triggerWatchOtaUpdate() {
        val existingBtFile = incomingWatchApkFile
        if (existingBtFile != null && existingBtFile.exists() && existingBtFile.length() > 0L) {
            WatchGithubUpdater.statusFlow.value = WatchGithubUpdater.promptInstallWatchApk(this, existingBtFile)
            return
        }
        lifecycleScope.launch {
            val downloaded = WatchGithubUpdater.downloadLatestWatchApkFromGithub(
                context = this@MainActivity,
                onStatus = { status -> WatchGithubUpdater.statusFlow.value = status }
            )
            if (downloaded != null) {
                incomingWatchApkFile = downloaded
                val res = WatchGithubUpdater.promptInstallWatchApk(this@MainActivity, downloaded)
                WatchGithubUpdater.statusFlow.value = res
            }
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, SensorWorkoutService::class.java).apply {
            this.action = action
        }
        runCatching {
            if (action == SensorWorkoutService.ACTION_START) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
        }
    }

    override fun onDestroy() {
        runCatching {
            Wearable.getChannelClient(this).unregisterChannelCallback(channelCallback)
        }
        super.onDestroy()
    }
}

private val BgPureBlack = Color(0xFF000000)
private val SurfaceZinc = Color(0xFF18181B)
private val BorderZinc = Color(0xFF27272A)
private val TextWhite = Color(0xFFF4F4F5)
private val TextMutedZinc = Color(0xFFA1A1AA)
private val AccentOrange = Color(0xFFF97316)
private val SignalRose = Color(0xFFFB7185)
private val SignalEmerald = Color(0xFF34D399)
private val SignalAmber = Color(0xFFFBBF24)

private val Zone1Gray = Color(0xFF94A3B8)
private val Zone2Blue = Color(0xFF38BDF8)
private val Zone3Green = Color(0xFF34D399)
private val Zone4Amber = Color(0xFFFBBF24)
private val Zone5Red = Color(0xFFF43F5E)

private fun watchHrZone(bpm: Int, maxHr: Int): Int {
    if (bpm <= 0) return 1
    val pct = bpm.toFloat() / maxHr.coerceAtLeast(120).toFloat()
    return when {
        pct < 0.60f -> 1
        pct <= 0.70f -> 2
        pct <= 0.80f -> 3
        pct <= 0.90f -> 4
        else -> 5
    }
}

private fun watchRpmZone(rpm: Float): Int = when {
    rpm < 55f -> 1
    rpm < 75f -> 2
    rpm < 90f -> 3
    rpm < 105f -> 4
    else -> 5
}

private fun watchZoneColor(zone: Int): Color = when (zone) {
    1 -> Zone1Gray
    2 -> Zone2Blue
    3 -> Zone3Green
    4 -> Zone4Amber
    else -> Zone5Red
}

private val TabularStyle = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun WatchSensorScreen(
    state: WatchWorkoutState,
    updateStatus: String,
    onToggleMode: () -> Unit,
    onToggleActive: () -> Unit,
    onToggleSim: () -> Unit,
    onRequestPermissions: () -> Unit,
    onTriggerGithubUpdate: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPureBlack)
            .padding(vertical = 16.dp, horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Top Safe Arc: Link Status + Mode Badge (tappable for SIM)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(SurfaceZinc)
                    .border(1.dp, BorderZinc, RoundedCornerShape(50))
                    .clickable { onToggleSim() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.isActive && state.connectedNodes > 0) SignalEmerald
                            else if (state.isActive) SignalAmber
                            else TextMutedZinc
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                val modeText = buildString {
                    append(if (state.mode == "ANKLE") "ANKLE" else "WRIST")
                    if (state.isSimulated) append(" (SIM)")
                }
                Text(
                    text = modeText,
                    color = AccentOrange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // 2. Center Focal Point: 44sp Tabular Metric + Secondary Pill + OTA Update Pill
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val activeHrZone = watchHrZone(state.bpm, state.maxHr)
                val activeRpmZone = watchRpmZone(state.rpm)
                val primaryColor = if (state.mode == "ANKLE") {
                    if (state.rpm >= 5f) watchZoneColor(activeRpmZone) else TextWhite
                } else {
                    if (state.bpm > 0) watchZoneColor(activeHrZone) else TextWhite
                }

                val primaryValue = if (state.mode == "ANKLE") {
                    state.rpm.roundToInt().toString()
                } else {
                    when {
                        state.bpm > 0 -> state.bpm.toString()
                        state.hrStatus == "NO PERM" -> "PERM"
                        state.isActive -> "..."
                        else -> "0"
                    }
                }
                val primaryUnit = if (state.mode == "ANKLE") {
                    if (state.rpm >= 5f) "RPM · Z$activeRpmZone" else "RPM CADENCE"
                } else {
                    when {
                        state.bpm > 0 -> "BPM · ZONE $activeHrZone"
                        state.hrStatus == "NO PERM" -> "TAP HR PILL TO ALLOW"
                        state.hrStatus == "OFF WRIST" -> "WEAR WATCH SNUGLY"
                        state.isActive -> "MEASURING PULSE..."
                        else -> "HEART RATE (BPM)"
                    }
                }

                Text(
                    text = primaryValue,
                    style = TabularStyle,
                    color = primaryColor,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = primaryUnit,
                    color = if (state.bpm > 0 || state.rpm >= 5f) primaryColor else TextMutedZinc,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val hrPillColor = if (state.bpm > 0) watchZoneColor(activeHrZone) else SignalRose
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceZinc)
                            .border(1.dp, if (state.bpm > 0) hrPillColor else BorderZinc, RoundedCornerShape(8.dp))
                            .clickable { onRequestPermissions() }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        val secondaryLabel = if (state.mode == "ANKLE") {
                            val hrDisplay = when {
                                state.bpm > 0 -> "${state.bpm} Z$activeHrZone"
                                state.hrStatus == "NO PERM" -> "ALLOW"
                                state.isActive -> "WAIT.."
                                else -> "0 BPM"
                            }
                            "HR: $hrDisplay"
                        } else {
                            if (state.bpm > 0) "Z$activeHrZone LOCKED" else state.hrStatus
                        }
                        Text(
                            text = secondaryLabel,
                            style = TabularStyle,
                            color = hrPillColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Direct GitHub OTA Update Trigger Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceZinc)
                            .border(1.dp, AccentOrange, RoundedCornerShape(8.dp))
                            .clickable { onTriggerGithubUpdate() }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = updateStatus,
                            style = TabularStyle,
                            color = AccentOrange,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 3. Bottom Safe Arc: Two 48dp-tall Pill Buttons (Mode & Start/Stop)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onToggleMode,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceZinc,
                        contentColor = TextWhite
                    )
                ) {
                    Text(
                        text = "Mode",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onToggleActive,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isActive) BorderZinc else AccentOrange,
                        contentColor = TextWhite
                    )
                ) {
                    Text(
                        text = if (state.isActive) "Stop" else "Start",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
