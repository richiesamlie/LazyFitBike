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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * Samsung Galaxy Watch 4 circular UI with inscribed safe octagon layout,
 * direct GitHub OTA updater, and Bluetooth ChannelClient APK relay receiver.
 */
class MainActivity : ComponentActivity() {

    private val updateStatusFlow = MutableStateFlow("OTA Update")
    private var incomingWatchApkFile: File? = null

    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            if (channel.path == WatchGithubUpdater.WATCH_APK_CHANNEL_PATH) {
                val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
                val destFile = File(updatesDir, "LazyFitBike-watch-bt.apk")
                incomingWatchApkFile = destFile
                updateStatusFlow.value = "Receiving BT APK..."
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
                    updateStatusFlow.value = "BT APK Ready: Install"
                    val msg = WatchGithubUpdater.promptInstallWatchApk(this@MainActivity, readyFile)
                    updateStatusFlow.value = msg
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val neededPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(Manifest.permission.BODY_SENSORS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        }

        runCatching {
            Wearable.getChannelClient(this).registerChannelCallback(channelCallback)
        }

        setContent {
            val workoutState by SensorWorkoutService.state.collectAsState()
            val updateStatus by updateStatusFlow.collectAsState()
            WatchSensorScreen(
                state = workoutState,
                updateStatus = updateStatus,
                onToggleMode = { sendServiceAction(SensorWorkoutService.ACTION_TOGGLE_MODE) },
                onToggleActive = {
                    val action = if (workoutState.isActive) {
                        SensorWorkoutService.ACTION_STOP
                    } else {
                        SensorWorkoutService.ACTION_START
                    }
                    sendServiceAction(action)
                },
                onToggleSim = { sendServiceAction(SensorWorkoutService.ACTION_TOGGLE_SIM) },
                onTriggerGithubUpdate = { triggerWatchOtaUpdate() }
            )
        }
    }

    private fun triggerWatchOtaUpdate() {
        val existingBtFile = incomingWatchApkFile
        if (existingBtFile != null && existingBtFile.exists() && existingBtFile.length() > 0L) {
            updateStatusFlow.value = WatchGithubUpdater.promptInstallWatchApk(this, existingBtFile)
            return
        }
        lifecycleScope.launch {
            val downloaded = WatchGithubUpdater.downloadLatestWatchApkFromGithub(
                context = this@MainActivity,
                onStatus = { status -> updateStatusFlow.value = status }
            )
            if (downloaded != null) {
                incomingWatchApkFile = downloaded
                val res = WatchGithubUpdater.promptInstallWatchApk(this@MainActivity, downloaded)
                updateStatusFlow.value = res
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

private val TabularStyle = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun WatchSensorScreen(
    state: WatchWorkoutState,
    updateStatus: String,
    onToggleMode: () -> Unit,
    onToggleActive: () -> Unit,
    onToggleSim: () -> Unit,
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
                val primaryValue = if (state.mode == "ANKLE") {
                    state.rpm.roundToInt().toString()
                } else {
                    if (state.bpm > 0) state.bpm.toString() else "0"
                }
                val primaryUnit = if (state.mode == "ANKLE") {
                    "RPM CADENCE"
                } else {
                    "HEART RATE (BPM)"
                }

                Text(
                    text = primaryValue,
                    style = TabularStyle,
                    color = TextWhite,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = primaryUnit,
                    color = TextMutedZinc,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceZinc)
                            .border(1.dp, BorderZinc, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        val secondaryLabel = if (state.mode == "ANKLE") {
                            val hrDisplay = if (state.bpm > 0) "${state.bpm} BPM" else "HR"
                            "HR: $hrDisplay"
                        } else {
                            "${state.rpm.roundToInt()} RPM"
                        }
                        Text(
                            text = secondaryLabel,
                            style = TabularStyle,
                            color = SignalRose,
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
