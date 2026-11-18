package com.fitness.zwiftbike

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val rideViewModel: RideViewModel = viewModel()
            val state by rideViewModel.uiState.collectAsState()
            val context = LocalContext.current

            val gpxLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        val xml = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        if (!xml.isNullOrBlank()) {
                            rideViewModel.importGpxRouteXml(xml)
                        }
                    }
                }
            }

            val zwoLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        val xml = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        if (!xml.isNullOrBlank()) {
                            rideViewModel.loadZwoFromXml(xml)
                        }
                    }
                }
            }

            val locationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                val granted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
                if (granted) {
                    rideViewModel.centerMapOnMyLocation(context)
                }
            }

            val workoutSummary by rideViewModel.workoutSummary.collectAsState()

            BackHandler {
                rideViewModel.handleBackPress()
            }

            LazyFitBikeCockpitScreen(
                state = state,
                onToggleSensorMode = rideViewModel::toggleSensorMode,
                onChangeGear = rideViewModel::changeGear,
                onCycleCamera = rideViewModel::cycleCameraMode,
                onToggleDemo = rideViewModel::toggleDemoMode,
                onDemoEffortChange = rideViewModel::setDemoEffort,
                onSelectPresetRoute = rideViewModel::selectPresetRoute,
                onOpenGpxPicker = { gpxLauncher.launch("*/*") },
                onRequestOsrmRoute = rideViewModel::requestCustomOsrmRoute,
                onLocateMeOnMap = {
                    val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (hasFine || hasCoarse) {
                        rideViewModel.centerMapOnMyLocation(context)
                    } else {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                },
                onSetMapModalOpen = rideViewModel::setMapModalOpen,
                onSetStravaModalOpen = rideViewModel::setStravaModalOpen,
                onSetUpdateModalOpen = rideViewModel::setUpdateModalOpen,
                onSetProfileModalOpen = rideViewModel::setProfileModalOpen,
                onSaveRiderProfile = rideViewModel::saveRiderProfile,
                onSetSpectatorUrl = rideViewModel::setSpectatorEndpointUrl,
                onSetGoogleMapsApiKey = rideViewModel::setGoogleMapsApiKey,
                onCheckGithubUpdates = rideViewModel::checkGithubUpdates,
                onDownloadPhoneUpdate = { rideViewModel.downloadAndInstallPhoneUpdate(context) },
                onRelayWatchUpdate = { rideViewModel.downloadAndRelayWatchUpdate(context) },
                onShareWatchApk = { rideViewModel.shareWatchUpdateApk(context) },
                onExportStravaGpx = { rideViewModel.exportAndShareStravaGpx(context) },
                onExportGoogleSheets = { rideViewModel.exportAndShareGoogleSheets(context) },
                onExportCanvaStory = { rideViewModel.exportAndShareCanvaStory(context) },
                onBackupGoogleDrive = { rideViewModel.backupRideToGoogleDrive(context) },
                onUploadDirectToStrava = rideViewModel::uploadDirectToStrava,
                onSetWorkoutModalOpen = rideViewModel::setWorkoutModalOpen,
                onSetWorkoutSummaryModalOpen = rideViewModel::setWorkoutSummaryModalOpen,
                onStartWorkout = rideViewModel::startWorkout,
                onStartAiWorkout = rideViewModel::startAiGeneratedWorkout,
                onScheduleWorkoutInCalendar = { rideViewModel.scheduleWorkoutInCalendar(context, it) },
                onPauseWorkout = rideViewModel::pauseWorkout,
                onResumeWorkout = rideViewModel::resumeWorkout,
                onSkipWorkoutStep = rideViewModel::skipWorkoutStep,
                onAdjustWorkoutIntensity = rideViewModel::adjustWorkoutIntensity,
                onStopWorkout = rideViewModel::stopWorkout,
                onOpenZwoPicker = { zwoLauncher.launch("*/*") },
                onApplyNewFtp = rideViewModel::applyNewFtpAndSync,
                workoutSummary = workoutSummary,
                onSetExitConfirmModalOpen = rideViewModel::setExitConfirmModalOpen,
                onConfirmExitApp = { finish() }
            )
        }
    }
}

private class MapJsBridge(
    private val onRouteRequested: (Double, Double, Double, Double) -> Unit,
    private val onLocateRequested: () -> Unit
) {
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @JavascriptInterface
    fun onMapRouteRequested(startLat: Double, startLon: Double, endLat: Double, endLon: Double) {
        mainHandler.post {
            onRouteRequested(startLat, startLon, endLat, endLon)
        }
    }

    @JavascriptInterface
    fun onLocateMeRequested() {
        mainHandler.post {
            onLocateRequested()
        }
    }
}

private object OsmTileProxy {
    private const val USER_AGENT = "LazyFitBike/${BuildConfig.VERSION_NAME} (https://github.com/richiesamlie/LazyFitBike)"
    private const val REFERER = "https://github.com/richiesamlie/LazyFitBike"
    private val tileUrlRegex = Regex("""/(\d+)/(\d+)/(\d+)\.png""")

    fun lonToTileX(lon: Double, zoom: Int): Double {
        return ((lon + 180.0) / 360.0) * 2.0.pow(zoom.toDouble())
    }

    fun latToTileY(lat: Double, zoom: Int): Double {
        val rad = lat * PI / 180.0
        return ((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0) * 2.0.pow(zoom.toDouble())
    }

    fun interceptIfTileRequest(context: Context, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null
        if (!url.contains("tile-cyclosm.openstreetmap.fr") &&
            !url.contains("tile.opentopomap.org") &&
            !url.contains("tile.openstreetmap.org") &&
            !url.contains("basemaps.cartocdn.com")
        ) {
            return null
        }
        val match = tileUrlRegex.find(url) ?: return null
        val z = match.groupValues[1].toIntOrNull() ?: return null
        val x = match.groupValues[2].toIntOrNull() ?: return null
        val y = match.groupValues[3].toIntOrNull() ?: return null

        val bytes = fetchTileBytes(context, z, x, y) ?: return null
        return WebResourceResponse(
            "image/png",
            null,
            200,
            "OK",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "max-age=604800"
            ),
            ByteArrayInputStream(bytes)
        )
    }

    fun fetchTileBytes(context: Context, z: Int, x: Int, y: Int): ByteArray? {
        val cacheDir = File(context.cacheDir, "map_tiles_v3")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val cacheFile = File(cacheDir, "${z}_${x}_${y}.png")
        if (cacheFile.exists() && cacheFile.length() > 256L) {
            runCatching { return cacheFile.readBytes() }
        }

        val candidateUrls = listOf(
            "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/$z/$x/$y.png",
            "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/$z/$x/$y.png",
            "https://a.tile.opentopomap.org/$z/$x/$y.png"
        )
        for (urlStr in candidateUrls) {
            val downloaded = runCatching {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Referer", REFERER)
                    setRequestProperty("Accept", "image/png,image/*;q=0.8,*/*;q=0.5")
                }
                if (conn.responseCode in 200..299 && conn.getHeaderField("x-blocked") == null) {
                    conn.inputStream.use { it.readBytes() }.takeIf { it.size > 256 }
                } else {
                    null
                }
            }.getOrNull()

            if (downloaded != null) {
                runCatching { cacheFile.writeBytes(downloaded) }
                return downloaded
            }
        }
        return null
    }
}

private val BgCarbonHud = Color(0xE60B1510)
private val BgControlDark = Color(0xFF13221A)
private val BorderZinc = Color(0xFF1E3328)
private val TextPrimaryWhite = Color(0xFFF4F7F2)
private val TextMutedZinc = Color(0xFFA9BDB0)
private val AccentLime = Color(0xFFC8F04A)
private val InkDark = Color(0xFF0B1510)
private val AccentForest = Color(0xFF1F5A3A)
private val SignalRose = Color(0xFFFB7185)
private val SignalEmerald = Color(0xFF34D399)
private val SignalAmber = Color(0xFFFBBF24)

private val Zone1Gray = Color(0xFF94A3B8)
private val Zone2Blue = Color(0xFF38BDF8)
private val Zone3Green = Color(0xFF34D399)
private val Zone4Amber = Color(0xFFFBBF24)
private val Zone5Red = Color(0xFFF43F5E)

private val TabularStyle = TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun lazyFitTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimaryWhite,
    unfocusedTextColor = TextPrimaryWhite,
    focusedContainerColor = BgControlDark,
    unfocusedContainerColor = BgControlDark,
    focusedBorderColor = AccentLime,
    unfocusedBorderColor = TextMutedZinc,
    focusedLabelColor = AccentLime,
    unfocusedLabelColor = TextMutedZinc,
    focusedPlaceholderColor = TextMutedZinc,
    unfocusedPlaceholderColor = TextMutedZinc,
    cursorColor = AccentLime
)

private fun pushRoutePolylineToWebView(view: WebView?, polylineJson: String) {
    val escaped = polylineJson.replace("'", "\\'")
    view?.evaluateJavascript(
        "if(window.setRoutePolyline){window.setRoutePolyline('$escaped');}",
        null
    )
}

private fun pushRideStateToWebView(view: WebView?, state: RideUiState) {
    val jsCall = String.format(
        Locale.US,
        "if(window.updateRideState){window.updateRideState(%.2f,%d,%d,%.2f,%.2f,'%s',%.6f,%.6f,%.3f,%b,%d,%.2f,%d,%d,%d,%d,'%s',%b,'%s',%.1f,%b);}",
        state.speedKmh,
        state.rpm,
        state.watts,
        state.slopeGradePercent,
        state.distanceMeters,
        state.cameraMode,
        state.currentLat,
        state.currentLon,
        state.currentCurvature,
        state.isMapModalOpen,
        state.bpm,
        state.wattsPerKg,
        state.ftpWatts,
        state.powerZone,
        state.hrZone,
        state.elapsedSeconds,
        state.activeRouteName.replace("'", "\\'"),
        state.isDrafting,
        state.nearestBotLabel.replace("'", "\\'"),
        state.activeRouteDistanceMeters,
        state.googleMapsApiKey.isNotBlank()
    )
    view?.evaluateJavascript(jsCall, null)

    val escapedWorkoutName = state.workoutName.replace("'", "\\'")
    val escapedStepName = state.workoutStepName.replace("'", "\\'")
    val escapedPrompt = state.workoutPacingPrompt.replace("'", "\\'")
    val workoutJs = String.format(
        Locale.US,
        "if(window.updateWorkoutHud){window.updateWorkoutHud(%b,'%s',%d,%d,%d,%d,%d,'%s',%.1f,%d,'%s',%d,%b);}",
        state.isWorkoutActive,
        escapedWorkoutName,
        state.workoutTargetWatts,
        state.workoutTargetRpm,
        state.workoutStepRemainingSec,
        state.workoutStepIndex,
        state.workoutTotalSteps,
        escapedStepName,
        state.workoutCompliancePercent,
        state.workoutEarnedStars,
        escapedPrompt,
        state.workoutIntensityBiasPercent,
        state.isWorkoutPaused
    )
    view?.evaluateJavascript(workoutJs, null)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LazyFitBikeCockpitScreen(
    state: RideUiState,
    onToggleSensorMode: () -> Unit,
    onChangeGear: (Int) -> Unit,
    onCycleCamera: () -> Unit,
    onToggleDemo: () -> Unit,
    onDemoEffortChange: (Float) -> Unit,
    onSelectPresetRoute: (String) -> Unit,
    onOpenGpxPicker: () -> Unit,
    onRequestOsrmRoute: (Double, Double, Double, Double) -> Unit,
    onLocateMeOnMap: () -> Unit = {},
    onSetMapModalOpen: (Boolean) -> Unit,
    onSetStravaModalOpen: (Boolean) -> Unit,
    onSetUpdateModalOpen: (Boolean) -> Unit,
    onSetProfileModalOpen: (Boolean) -> Unit,
    onSaveRiderProfile: (Float, Float, Int, Int, Int, Int) -> Unit,
    onSetSpectatorUrl: (String) -> Unit = {},
    onSetGoogleMapsApiKey: (String) -> Unit = {},
    onCheckGithubUpdates: () -> Unit,
    onDownloadPhoneUpdate: () -> Unit,
    onRelayWatchUpdate: () -> Unit,
    onShareWatchApk: () -> Unit,
    onExportStravaGpx: () -> Unit,
    onExportGoogleSheets: () -> Unit = {},
    onExportCanvaStory: () -> Unit = {},
    onBackupGoogleDrive: () -> Unit = {},
    onUploadDirectToStrava: (String) -> Unit,
    onSetWorkoutModalOpen: (Boolean) -> Unit,
    onSetWorkoutSummaryModalOpen: (Boolean) -> Unit,
    onStartWorkout: (Workout) -> Unit,
    onStartAiWorkout: (String) -> Unit = {},
    onScheduleWorkoutInCalendar: (Workout) -> Unit = {},
    onPauseWorkout: () -> Unit,
    onResumeWorkout: () -> Unit,
    onSkipWorkoutStep: () -> Unit,
    onAdjustWorkoutIntensity: (Int) -> Unit,
    onStopWorkout: () -> Unit,
    onOpenZwoPicker: () -> Unit,
    onApplyNewFtp: (Int) -> Unit,
    workoutSummary: WorkoutSummary?,
    onSetExitConfirmModalOpen: (Boolean) -> Unit = {},
    onConfirmExitApp: () -> Unit = {}
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var stravaAccessToken by remember { mutableStateOf("") }
    var aiWorkoutPrompt by remember { mutableStateOf("45 min sweet spot climbing session") }
    var isMenuExpanded by remember { mutableStateOf(false) }
    val latestState by rememberUpdatedState(state)

    LaunchedEffect(state.activeRouteId, state.activeRoutePolylineJson) {
        pushRoutePolylineToWebView(webViewRef, state.activeRoutePolylineJson)
    }

    LaunchedEffect(state.mapCenterTriggerId) {
        if (state.mapCenterTriggerId > 0L) {
            val js = String.format(
                Locale.US,
                "if(window.centerMapAtLocation){window.centerMapAtLocation(%.6f,%.6f,true);}",
                state.mapCenterLat,
                state.mapCenterLon
            )
            webViewRef?.evaluateJavascript(js, null)
        }
    }

    LaunchedEffect(
        state.speedKmh,
        state.rpm,
        state.watts,
        state.slopeGradePercent,
        state.distanceMeters,
        state.cameraMode,
        state.currentLat,
        state.currentLon,
        state.currentCurvature,
        state.isMapModalOpen,
        state.bpm,
        state.wattsPerKg,
        state.ftpWatts,
        state.powerZone,
        state.hrZone,
        state.elapsedSeconds,
        state.activeRouteName,
        state.isDrafting,
        state.nearestBotLabel,
        state.activeRouteDistanceMeters,
        state.googleMapsApiKey,
        state.isWorkoutActive,
        state.workoutName,
        state.workoutTargetWatts,
        state.workoutTargetRpm,
        state.workoutStepRemainingSec,
        state.workoutStepIndex,
        state.workoutTotalSteps,
        state.workoutStepName,
        state.workoutCompliancePercent,
        state.workoutEarnedStars,
        state.workoutPacingPrompt,
        state.workoutIntensityBiasPercent,
        state.isWorkoutPaused
    ) {
        pushRideStateToWebView(webViewRef, state)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(InkDark)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val appCtx = context.applicationContext
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "LazyFitBike/${BuildConfig.VERSION_NAME} (https://github.com/richiesamlie/LazyFitBike)"
                    addJavascriptInterface(MapJsBridge(onRequestOsrmRoute, onLocateMeOnMap), "AndroidBridge")
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            return OsmTileProxy.interceptIfTileRequest(appCtx, request)
                                ?: super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            val current = latestState
                            pushRoutePolylineToWebView(view, current.activeRoutePolylineJson)
                            pushRideStateToWebView(view, current)
                        }
                    }
                    loadUrl("file:///android_asset/world3d.html")
                    webViewRef = this
                }
            },
            update = { view ->
                webViewRef = view
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // TOP WATCH CONNECTION STATUS BANNER (Only shown when waiting or signal lost)
            if (state.connectionState == ConnectionState.WAITING_FOR_WATCH ||
                state.connectionState == ConnectionState.SIGNAL_LOST
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(BgCarbonHud)
                            .border(1.dp, SignalAmber, RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val statusMsg = if (state.connectionState == ConnectionState.WAITING_FOR_WATCH) {
                            "Waiting for Galaxy Watch 4: tap Start on watch, or test with Demo Mode."
                        } else {
                            "Watch telemetry paused (>3s). Check Bluetooth/Wi-Fi or switch to Demo Mode."
                        }
                        Text(
                            text = statusMsg,
                            color = TextPrimaryWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Button(
                            onClick = onToggleDemo,
                            modifier = Modifier.heightIn(min = 36.dp),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentLime,
                                contentColor = InkDark
                            )
                        ) {
                            Text(
                                text = "Enable Demo Slider",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(1.dp))
            }

            // CENTER MODALS (Map Modal, Strava Modal, or GitHub OTA Update Modal)
            if (state.isMapModalOpen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 6.dp)
                ) {
                    HudPod(
                        modifier = Modifier
                            .width(290.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ONLINE MAPS & ROUTES",
                                color = AccentLime,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                            Button(
                                onClick = { onSetMapModalOpen(false) },
                                modifier = Modifier.heightIn(min = 36.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgControlDark,
                                    contentColor = TextPrimaryWhite
                                )
                            ) {
                                Text(text = "Close", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = onLocateMeOnMap,
                                modifier = Modifier
                                    .weight(1.25f)
                                    .heightIn(min = 46.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AccentLime,
                                    contentColor = InkDark
                                )
                            ) {
                                Text(
                                    text = "Locate GPS",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                            Button(
                                onClick = onOpenGpxPicker,
                                modifier = Modifier
                                    .weight(0.75f)
                                    .heightIn(min = 46.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgControlDark,
                                    contentColor = TextPrimaryWhite
                                )
                            ) {
                                Text(
                                    text = "Import .GPX",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (state.statusBannerMessage.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.statusBannerMessage,
                                color = SignalEmerald,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        MiniGpsRouteCanvas(
                            waypoints = state.activeRouteWaypoints,
                            lat = state.currentLat,
                            lon = state.currentLon
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        val isPresetSelected = GpsRouteEngine.presetRoutes.any { it.id == state.activeRouteId }
                        if (!isPresetSelected) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x28C8F04A))
                                    .border(1.dp, AccentLime, RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = state.activeRouteName,
                                        color = AccentLime,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        text = String.format(
                                            Locale.US,
                                            "%s : %.1f km : +%.0fm",
                                            state.activeRouteRegion,
                                            state.activeRouteDistanceMeters / 1000f,
                                            state.activeRouteWaypoints.zipWithNext { a, b -> (b.ele - a.ele).coerceAtLeast(0.0) }.sum()
                                        ),
                                        style = TabularStyle,
                                        color = TextPrimaryWhite,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        GpsRouteEngine.presetRoutes.forEach { preset ->
                            val isSelected = preset.id == state.activeRouteId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0x28C8F04A) else BgControlDark)
                                    .border(
                                        1.dp,
                                        if (isSelected) AccentLime else BorderZinc,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { onSelectPresetRoute(preset.id) }
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preset.name,
                                        color = if (isSelected) AccentLime else TextPrimaryWhite,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = String.format(
                                            Locale.US,
                                            "%s : %.1f km : +%.0fm",
                                            preset.region,
                                            preset.totalDistanceMeters / 1000f,
                                            preset.totalElevationGainMeters
                                        ),
                                        style = TabularStyle,
                                        color = TextMutedZinc,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        var gmpKeyInput by remember(state.googleMapsApiKey) { mutableStateOf(state.googleMapsApiKey) }
                        val hasGmpKey = state.googleMapsApiKey.isNotBlank()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ROUTING ENGINE",
                                color = TextMutedZinc,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (hasGmpKey) "GOOGLE MAPS + ELEVATION" else "OSRM FREE ROUTING",
                                color = if (hasGmpKey) SignalEmerald else AccentLime,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        OutlinedTextField(
                            value = gmpKeyInput,
                            onValueChange = { gmpKeyInput = it.trim() },
                            label = {
                                Text(
                                    text = "Google Maps API Key (Routes v2 + Elevation)",
                                    fontSize = 10.sp
                                )
                            },
                            placeholder = {
                                Text(
                                    text = "AIzaSy... (Leave blank for free OSRM)",
                                    fontSize = 10.sp
                                )
                            },
                            singleLine = true,
                            colors = lazyFitTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = { onSetGoogleMapsApiKey(gmpKeyInput) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hasGmpKey) SignalEmerald else BgControlDark,
                                contentColor = if (hasGmpKey) InkDark else TextPrimaryWhite
                            )
                        ) {
                            Text(
                                text = if (gmpKeyInput.isNotBlank()) "Activate Google Maps Routing" else "Use Free OSRM Routing",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (state.statusBannerMessage.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = state.statusBannerMessage,
                                color = SignalEmerald,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            } else if (state.isStravaModalOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    HudPod(
                        modifier = Modifier
                            .width(480.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "STRAVA CO-OP & GPX TELEMETRY SYNC",
                                color = AccentLime,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black
                            )
                            Button(
                                onClick = { onSetStravaModalOpen(false) },
                                modifier = Modifier.heightIn(min = 36.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgControlDark,
                                    contentColor = TextPrimaryWhite
                                )
                            ) {
                                Text(text = "Resume Ride", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BgControlDark)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            MetricColumn(
                                label = "ROUTE DIST",
                                value = String.format(Locale.US, "%.2f", state.distanceKm),
                                unit = "km"
                            )
                            MetricColumn(
                                label = "AVG POWER",
                                value = "${state.avgWatts}",
                                unit = "Watts"
                            )
                            MetricColumn(
                                label = "AVG HR / CAD",
                                value = "${state.avgBpm}/${state.avgRpm}",
                                unit = "BPM / RPM"
                            )
                            MetricColumn(
                                label = "GPS POINTS",
                                value = "${state.recordedTrackPointsCount}",
                                unit = "1 Hz trkpts"
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = onExportStravaGpx,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentLime,
                                contentColor = InkDark
                            )
                        ) {
                            Text(
                                text = "1. Export & Share .GPX File (GPS + HR + Cadence + Watts)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = stravaAccessToken,
                            onValueChange = { stravaAccessToken = it },
                            label = {
                                Text(
                                    text = "Optional: Strava OAuth2 Access Token (activity:write)",
                                    fontSize = 11.sp
                                )
                            },
                            singleLine = true,
                            colors = lazyFitTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Button(
                            onClick = { onUploadDirectToStrava(stravaAccessToken) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BgControlDark,
                                contentColor = TextPrimaryWhite
                            )
                        ) {
                            Text(
                                text = "2. Direct Upload to Strava v3 API",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onExportGoogleSheets,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 42.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SignalEmerald,
                                    contentColor = InkDark
                                )
                            ) {
                                Text("Sheets CSV", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = onExportCanvaStory,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 42.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AccentForest,
                                    contentColor = TextPrimaryWhite
                                )
                            ) {
                                Text("Canva Story", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = onBackupGoogleDrive,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 42.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgControlDark,
                                    contentColor = TextPrimaryWhite
                                )
                            ) {
                                Text("Drive Vault", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (state.statusBannerMessage.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = state.statusBannerMessage,
                                color = SignalEmerald,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            } else if (state.isUpdateModalOpen) {
                // GITHUB OTA SELF-UPDATE MODAL (Phone + Galaxy Watch 4)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    HudPod(
                        modifier = Modifier
                            .width(480.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "GITHUB RELEASES OTA UPDATER",
                                    color = AccentLime,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = "Repo: richiesamlie/LazyFitBike",
                                    color = TextMutedZinc,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Button(
                                onClick = { onSetUpdateModalOpen(false) },
                                modifier = Modifier.heightIn(min = 36.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgControlDark,
                                    contentColor = TextPrimaryWhite
                                )
                            ) {
                                Text(text = "Close", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BgControlDark)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            MetricColumn(
                                label = "INSTALLED",
                                value = state.installedVersionName,
                                unit = "Phone & Watch"
                            )
                            MetricColumn(
                                label = "GITHUB LATEST",
                                value = state.latestReleaseTag,
                                unit = "Release Tag"
                            )
                            MetricColumn(
                                label = "DOWNLOAD",
                                value = "${state.updateDownloadProgress}%",
                                unit = "Progress"
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        LinearProgressIndicator(
                            progress = { (state.updateDownloadProgress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = AccentLime,
                            trackColor = BgControlDark
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = state.updateStatusMessage,
                            style = TabularStyle,
                            color = SignalEmerald,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onCheckGithubUpdates,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgControlDark,
                                    contentColor = TextPrimaryWhite
                                )
                            ) {
                                Text(
                                    text = "Check GitHub",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            val isPhoneUpToDate = state.installedVersionName.removePrefix("v")
                                .equals(state.latestReleaseTag.removePrefix("v"), ignoreCase = true)
                            Button(
                                onClick = onDownloadPhoneUpdate,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPhoneUpToDate) BgControlDark else AccentLime,
                                    contentColor = if (isPhoneUpToDate) SignalEmerald else InkDark
                                )
                            ) {
                                Text(
                                    text = if (isPhoneUpToDate) "Up to Date (${state.installedVersionName})" else "Update Phone APK",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Button(
                            onClick = onRelayWatchUpdate,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BgControlDark,
                                contentColor = TextPrimaryWhite
                            )
                        ) {
                            Text(
                                text = "Download & Push Watch APK via Bluetooth",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Button(
                            onClick = onShareWatchApk,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BgControlDark,
                                contentColor = AccentLime
                            )
                        ) {
                            Text(
                                text = "Share Watch APK (Wear Installer 2 / Bugjaeger / Files)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // RIDER PROFILE & 5-ZONE CALIBRATION MODAL
            if (state.isProfileModalOpen) {
                var weightInput by remember(state.riderWeightKg) {
                    mutableStateOf(state.riderWeightKg.toInt().toString())
                }
                var bikeWeightInput by remember(state.bikeWeightKg) {
                    mutableStateOf(state.bikeWeightKg.toInt().toString())
                }
                var ftpInput by remember(state.ftpWatts) {
                    mutableStateOf(state.ftpWatts.toString())
                }
                var restHrInput by remember(state.restingHr) {
                    mutableStateOf(state.restingHr.toString())
                }
                var maxHrInput by remember(state.maxHr) {
                    mutableStateOf(state.maxHr.toString())
                }
                var ageInput by remember(state.riderAge) {
                    mutableStateOf(state.riderAge.toString())
                }
                var spectatorUrlInput by remember(state.spectatorEndpointUrl) {
                    mutableStateOf(state.spectatorEndpointUrl)
                }

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    HudPod(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .verticalScroll(rememberScrollState()),
                        borderColor = AccentLime
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "RIDER PROFILE & 5-ZONE CALIBRATION",
                                color = AccentLime,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black
                            )
                            Button(
                                onClick = { onSetProfileModalOpen(false) },
                                modifier = Modifier.heightIn(min = 36.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgControlDark,
                                    contentColor = TextPrimaryWhite
                                )
                            ) {
                                Text("Close", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = weightInput,
                                onValueChange = { weightInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                label = { Text("Rider Weight (kg)", fontSize = 10.sp) },
                                singleLine = true,
                                colors = lazyFitTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = bikeWeightInput,
                                onValueChange = { bikeWeightInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                label = { Text("Bike Weight (kg)", fontSize = 10.sp) },
                                singleLine = true,
                                colors = lazyFitTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = ageInput,
                                onValueChange = { ageInput = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Age (yrs)", fontSize = 10.sp) },
                                singleLine = true,
                                colors = lazyFitTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = ftpInput,
                                onValueChange = { ftpInput = it.filter { ch -> ch.isDigit() } },
                                label = { Text("FTP Power (Watts)", fontSize = 10.sp) },
                                singleLine = true,
                                colors = lazyFitTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = restHrInput,
                                onValueChange = { restHrInput = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Resting HR (BPM)", fontSize = 10.sp) },
                                singleLine = true,
                                colors = lazyFitTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = maxHrInput,
                                onValueChange = { maxHrInput = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Max HR (BPM)", fontSize = 10.sp) },
                                singleLine = true,
                                colors = lazyFitTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "1-TAP AUTO-CALCULATORS (AGE & FITNESS LEVEL)",
                            color = TextMutedZinc,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = {
                                    val ageVal = ageInput.toIntOrNull() ?: 30
                                    maxHrInput = CyclingPhysics.estimateMaxHrFromAge(ageVal).toString()
                                },
                                modifier = Modifier.heightIn(min = 38.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgControlDark,
                                    contentColor = Zone2Blue
                                )
                            ) {
                                Text("Calc Max HR from Age", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = {
                                    val wVal = weightInput.toFloatOrNull() ?: 70f
                                    ftpInput = CyclingPhysics.estimateFtpFromWeightAndLevel(wVal, "BEGINNER").toString()
                                },
                                modifier = Modifier.heightIn(min = 38.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgControlDark,
                                    contentColor = Zone3Green
                                )
                            ) {
                                Text("Beginner FTP (2.0 W/kg)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = {
                                    val wVal = weightInput.toFloatOrNull() ?: 70f
                                    ftpInput = CyclingPhysics.estimateFtpFromWeightAndLevel(wVal, "INTERMEDIATE").toString()
                                },
                                modifier = Modifier.heightIn(min = 38.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgControlDark,
                                    contentColor = Zone4Amber
                                )
                            ) {
                                Text("Moderate FTP (2.8 W/kg)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = {
                                    val wVal = weightInput.toFloatOrNull() ?: 70f
                                    ftpInput = CyclingPhysics.estimateFtpFromWeightAndLevel(wVal, "ADVANCED").toString()
                                },
                                modifier = Modifier.heightIn(min = 38.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgControlDark,
                                    contentColor = Zone5Red
                                )
                            ) {
                                Text("Pro FTP (3.6 W/kg)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Spectator URL Configuration
                        OutlinedTextField(
                            value = spectatorUrlInput,
                            onValueChange = { spectatorUrlInput = it.trim() },
                            label = { Text("Live Spectator URL (Vercel / Next.js)", fontSize = 10.sp) },
                            placeholder = { Text("https://spectator.vercel.app/api/telemetry", fontSize = 10.sp) },
                            singleLine = true,
                            colors = lazyFitTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                onSaveRiderProfile(
                                    weightInput.toFloatOrNull() ?: state.riderWeightKg,
                                    bikeWeightInput.toFloatOrNull() ?: state.bikeWeightKg,
                                    ftpInput.toIntOrNull() ?: state.ftpWatts,
                                    restHrInput.toIntOrNull() ?: state.restingHr,
                                    maxHrInput.toIntOrNull() ?: state.maxHr,
                                    ageInput.toIntOrNull() ?: state.riderAge
                                )
                                onSetSpectatorUrl(spectatorUrlInput)
                                onSetProfileModalOpen(false)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 46.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentLime,
                                contentColor = InkDark
                            )
                        ) {
                            Text(
                                text = "Save Profile & Sync Zones to Galaxy Watch",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }

            // STRUCTURED WORKOUT PICKER MODAL
            if (state.isWorkoutModalOpen) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    HudPod(
                        modifier = Modifier
                            .fillMaxWidth(0.94f)
                            .fillMaxHeight(0.85f),
                        borderColor = AccentLime
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "STRUCTURED WORKOUT LIBRARY",
                                    color = AccentLime,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = onOpenZwoPicker,
                                        modifier = Modifier.heightIn(min = 36.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = AccentForest,
                                            contentColor = TextPrimaryWhite
                                        )
                                    ) {
                                        Text("Import .ZWO", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = { onSetWorkoutModalOpen(false) },
                                        modifier = Modifier.heightIn(min = 36.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = BgControlDark,
                                            contentColor = TextPrimaryWhite
                                        )
                                    ) {
                                        Text("Close", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Text(
                                text = "Select a precision training session scaled to your current FTP (${state.ftpWatts} W):",
                                color = TextMutedZinc,
                                fontSize = 11.sp
                            )

                            for (w in WorkoutEngine.presetWorkouts) {
                                val mins = w.totalDurationSeconds / 60
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(BgControlDark)
                                        .border(1.dp, BorderZinc, RoundedCornerShape(8.dp))
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = w.name,
                                                color = TextPrimaryWhite,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            val badgeBg = when (w.category) {
                                                WorkoutType.RAMP_TEST -> AccentLime
                                                WorkoutType.VO2_MAX -> SignalRose
                                                WorkoutType.SWEET_SPOT -> AccentForest
                                                WorkoutType.THRESHOLD -> SignalAmber
                                                else -> SignalEmerald
                                            }
                                            val badgeFg = if (w.category == WorkoutType.SWEET_SPOT) TextPrimaryWhite else InkDark
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(badgeBg)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = w.category.name,
                                                    color = badgeFg,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Black
                                                )
                                            }
                                            Text(
                                                text = "$mins mins · ${w.steps.size} steps",
                                                color = TextMutedZinc,
                                                fontSize = 11.sp
                                            )
                                        }
                                        Text(
                                            text = w.description,
                                            color = TextMutedZinc,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 10.dp)
                                    ) {
                                        Button(
                                            onClick = { onScheduleWorkoutInCalendar(w) },
                                            modifier = Modifier.heightIn(min = 40.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = AccentForest,
                                                contentColor = TextPrimaryWhite
                                            )
                                        ) {
                                            Text("Schedule", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Button(
                                            onClick = { onStartWorkout(w) },
                                            modifier = Modifier.heightIn(min = 40.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = AccentLime,
                                                contentColor = InkDark
                                            )
                                        ) {
                                            Text("Start", fontSize = 11.sp, fontWeight = FontWeight.Black)
                                        }
                                    }
                                }
                            }

                            // AI Workout Creator (Gemini Coach Integration)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BgControlDark)
                                    .border(1.dp, AccentForest, RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "AI WORKOUT CREATOR (GEMINI)",
                                        color = AccentLime,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    OutlinedTextField(
                                        value = aiWorkoutPrompt,
                                        onValueChange = { aiWorkoutPrompt = it },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                                        textStyle = TextStyle(color = TextPrimaryWhite, fontSize = 11.sp),
                                        placeholder = { Text("e.g. 30 min VO2 max attack", fontSize = 11.sp, color = TextMutedZinc) },
                                        singleLine = true,
                                        colors = lazyFitTextFieldColors()
                                    )
                                }
                                Button(
                                    onClick = { onStartAiWorkout(aiWorkoutPrompt) },
                                    modifier = Modifier.heightIn(min = 40.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AccentLime,
                                        contentColor = InkDark
                                    )
                                ) {
                                    Text("Generate & Ride", fontSize = 11.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                }
            }

            // POST-WORKOUT SUMMARY ANALYTICS MODAL
            if (state.isWorkoutSummaryModalOpen && workoutSummary != null) {
                val sum = workoutSummary
                val sumMins = sum.totalTimeSeconds / 60
                val sumSecs = sum.totalTimeSeconds % 60
                val durationStr = String.format(Locale.US, "%02d:%02d", sumMins, sumSecs)

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    HudPod(
                        modifier = Modifier
                            .fillMaxWidth(0.94f)
                            .fillMaxHeight(0.88f)
                            .verticalScroll(rememberScrollState()),
                        borderColor = SignalEmerald
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "WORKOUT COMPLETE: ${sum.workoutName.uppercase()}",
                                    color = SignalEmerald,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Button(
                                    onClick = { onSetWorkoutSummaryModalOpen(false) },
                                    modifier = Modifier.heightIn(min = 36.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = BgControlDark,
                                        contentColor = TextPrimaryWhite
                                    )
                                ) {
                                    Text("Close", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            // New FTP Detected Card (from Ramp Test)
                            if (sum.detectedFtpWatts != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(AccentForest)
                                        .border(1.dp, AccentLime, RoundedCornerShape(8.dp))
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "NEW ESTIMATED FTP: ${sum.detectedFtpWatts} W",
                                            color = AccentLime,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                        Text(
                                            text = "Previous FTP: ${sum.previousFtpWatts} W (${if (sum.detectedFtpWatts >= sum.previousFtpWatts) "+" else ""}${sum.detectedFtpWatts - sum.previousFtpWatts} W)",
                                            color = TextPrimaryWhite,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Button(
                                        onClick = { onApplyNewFtp(sum.detectedFtpWatts) },
                                        shape = RoundedCornerShape(6.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = AccentLime,
                                            contentColor = InkDark
                                        )
                                    ) {
                                        Text("Apply & Sync", fontSize = 11.sp, fontWeight = FontWeight.Black)
                                    }
                                }
                            }

                            // Coggan Metrics Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(BgControlDark)
                                        .padding(10.dp)
                                ) {
                                    MetricColumn(label = "TSS (STRESS)", value = "${sum.tss}", unit = "PTS")
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(BgControlDark)
                                        .padding(10.dp)
                                ) {
                                    MetricColumn(label = "NORM POWER", value = "${sum.normalizedPowerWatts}", unit = "W")
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(BgControlDark)
                                        .padding(10.dp)
                                ) {
                                    MetricColumn(
                                        label = "INTENSITY",
                                        value = String.format(Locale.US, "%.2f", sum.intensityFactor),
                                        unit = "IF"
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(BgControlDark)
                                        .padding(10.dp)
                                ) {
                                    MetricColumn(
                                        label = "COMPLIANCE",
                                        value = "${sum.overallCompliancePercent.toInt()}%",
                                        unit = "${sum.totalStarsEarned}/${sum.maxStarsPossible} STARS"
                                    )
                                }
                            }

                            // Secondary Metrics
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(BgControlDark)
                                        .padding(10.dp)
                                ) {
                                    MetricColumn(label = "DURATION", value = durationStr, unit = "TIME")
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(BgControlDark)
                                        .padding(10.dp)
                                ) {
                                    MetricColumn(label = "AVG POWER", value = "${sum.avgWatts}", unit = "W")
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(BgControlDark)
                                        .padding(10.dp)
                                ) {
                                    MetricColumn(label = "MAX POWER", value = "${sum.maxWatts}", unit = "W")
                                }
                            }

                            // AI Coach Debrief (Gemini Sports Science)
                            if (state.aiCoachDebrief.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(BgControlDark)
                                        .border(1.dp, AccentForest, RoundedCornerShape(8.dp))
                                        .padding(12.dp)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "AI COACH DEBRIEF (GEMINI SPORTS SCIENCE)",
                                            color = AccentLime,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                        Text(
                                            text = state.aiCoachDebrief,
                                            color = TextPrimaryWhite,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }

                            // Primary Hero Action: Strava
                            Button(
                                onClick = onExportStravaGpx,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AccentLime,
                                    contentColor = InkDark
                                )
                            ) {
                                Text("Share Workout to Strava (GPX)", fontSize = 13.sp, fontWeight = FontWeight.Black)
                            }

                            // Secondary Cloud & Social Exports Tray
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onExportGoogleSheets,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 40.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SignalEmerald,
                                        contentColor = InkDark
                                    )
                                ) {
                                    Text("Sheets CSV", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = onExportCanvaStory,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 40.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AccentForest,
                                        contentColor = TextPrimaryWhite
                                    )
                                ) {
                                    Text("Canva Story", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = onBackupGoogleDrive,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 40.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = BgControlDark,
                                        contentColor = TextPrimaryWhite
                                    )
                                ) {
                                    Text("Drive Vault", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // EXIT CONFIRMATION MODAL
            if (state.isExitConfirmModalOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    HudPod(
                        modifier = Modifier.width(420.dp),
                        borderColor = SignalRose
                    ) {
                        Text(
                            text = "EXIT LAZYFITBIKE?",
                            color = SignalRose,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        val exitSubtitle = when {
                            state.isWorkoutActive -> "An active workout (${state.workoutName}) is in progress. Exiting now will close the cockpit."
                            state.recordedTrackPointsCount > 0 -> "You have ${state.recordedTrackPointsCount} recorded telemetry points (${String.format(Locale.US, "%.2f", state.distanceKm)} km) in this session."
                            else -> "Are you sure you want to close the LazyFitBike cockpit?"
                        }
                        Text(
                            text = exitSubtitle,
                            color = TextPrimaryWhite,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onSetExitConfirmModalOpen(false) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgControlDark,
                                    contentColor = TextPrimaryWhite
                                )
                            ) {
                                Text(
                                    text = "Resume Ride",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Button(
                                onClick = onConfirmExitApp,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SignalRose,
                                    contentColor = Color.Black
                                )
                            ) {
                                Text(
                                    text = "Exit App",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }
            }

            // IN-RIDE WORKOUT QUICK CONTROLS BAR (Active during workout)
            if (state.isWorkoutActive) {
                HudPod(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = AccentLime
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (state.isWorkoutPaused) onResumeWorkout() else onPauseWorkout()
                                },
                                modifier = Modifier.heightIn(min = 52.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (state.isWorkoutPaused) AccentLime else BgControlDark,
                                    contentColor = if (state.isWorkoutPaused) InkDark else TextPrimaryWhite
                                )
                            ) {
                                Text(
                                    text = if (state.isWorkoutPaused) "Resume" else "Pause",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                            Button(
                                onClick = onSkipWorkoutStep,
                                modifier = Modifier.heightIn(min = 52.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgControlDark,
                                    contentColor = TextPrimaryWhite
                                )
                            ) {
                                Text("Skip Step", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Bias:", color = TextMutedZinc, fontSize = 11.sp)
                            Button(
                                onClick = { onAdjustWorkoutIntensity(-5) },
                                modifier = Modifier.heightIn(min = 36.dp),
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgControlDark,
                                    contentColor = TextPrimaryWhite
                                ),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("-5%", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = (if (state.workoutIntensityBiasPercent > 0) "+" else "") + "${state.workoutIntensityBiasPercent}%",
                                color = if (state.workoutIntensityBiasPercent != 0) AccentLime else TextPrimaryWhite,
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                style = TabularStyle
                            )
                            Button(
                                onClick = { onAdjustWorkoutIntensity(5) },
                                modifier = Modifier.heightIn(min = 36.dp),
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgControlDark,
                                    contentColor = TextPrimaryWhite
                                ),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("+5%", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = onStopWorkout,
                            modifier = Modifier.heightIn(min = 44.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF7F1D1D),
                                contentColor = Color(0xFFFCA5A5)
                            )
                        ) {
                            Text("End", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // BOTTOM PERIMETER ROW
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                HudPod(modifier = Modifier.weight(1f, fill = false)) {
                    val isDemoActive = state.connectionState == ConnectionState.DEMO_MODE
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { isMenuExpanded = !isMenuExpanded },
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isMenuExpanded) AccentLime else BgControlDark,
                                contentColor = if (isMenuExpanded) InkDark else AccentLime
                            ),
                            border = if (isMenuExpanded) {
                                null
                            } else {
                                androidx.compose.foundation.BorderStroke(1.dp, AccentLime)
                            }
                        ) {
                            Text(
                                text = if (isMenuExpanded) "Hide Menu" else "Menu",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        if (!isMenuExpanded) {
                            CameraCycleButton(
                                cameraMode = state.cameraMode,
                                onCycleCamera = onCycleCamera
                            )
                            if (isDemoActive) {
                                DemoToggleAndSlider(
                                    isDemoActive = true,
                                    demoSliderEffort = state.demoSliderEffort,
                                    onToggleDemo = onToggleDemo,
                                    onDemoEffortChange = onDemoEffortChange
                                )
                            }
                        } else {
                            Button(
                                onClick = { onSetMapModalOpen(!state.isMapModalOpen) },
                                modifier = Modifier.heightIn(min = 48.dp),
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (state.isMapModalOpen) AccentLime else BgControlDark,
                                    contentColor = if (state.isMapModalOpen) InkDark else TextPrimaryWhite
                                )
                            ) {
                                Text(
                                    text = "Routes & Map",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Button(
                                onClick = { onSetWorkoutModalOpen(!state.isWorkoutModalOpen) },
                                modifier = Modifier.heightIn(min = 48.dp),
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (state.isWorkoutModalOpen || state.isWorkoutActive) {
                                        AccentLime
                                    } else {
                                        BgControlDark
                                    },
                                    contentColor = if (state.isWorkoutModalOpen || state.isWorkoutActive) {
                                        InkDark
                                    } else {
                                        TextPrimaryWhite
                                    }
                                )
                            ) {
                                Text(
                                    text = if (state.isWorkoutActive) "Workout (Active)" else "Workout",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            CameraCycleButton(
                                cameraMode = state.cameraMode,
                                onCycleCamera = onCycleCamera
                            )

                            Button(
                                onClick = onToggleSensorMode,
                                modifier = Modifier.heightIn(min = 48.dp),
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgControlDark,
                                    contentColor = if (state.sensorMode == "WRIST") SignalRose else AccentLime
                                )
                            ) {
                                Text(
                                    text = "Mode: ${state.sensorMode}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Button(
                                onClick = { onSetStravaModalOpen(!state.isStravaModalOpen) },
                                modifier = Modifier.heightIn(min = 48.dp),
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (state.isStravaModalOpen) AccentLime else BgControlDark,
                                    contentColor = if (state.isStravaModalOpen) InkDark else TextPrimaryWhite
                                )
                            ) {
                                Text(
                                    text = "Strava · ${state.recordedTrackPointsCount} pts",
                                    style = TabularStyle,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Button(
                                onClick = { onSetProfileModalOpen(!state.isProfileModalOpen) },
                                modifier = Modifier.heightIn(min = 48.dp),
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (state.isProfileModalOpen) AccentLime else BgControlDark,
                                    contentColor = if (state.isProfileModalOpen) InkDark else TextPrimaryWhite
                                )
                            ) {
                                Text(
                                    text = "Profile",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            DemoToggleAndSlider(
                                isDemoActive = isDemoActive,
                                demoSliderEffort = state.demoSliderEffort,
                                onToggleDemo = onToggleDemo,
                                onDemoEffortChange = onDemoEffortChange
                            )

                            Button(
                                onClick = { onSetUpdateModalOpen(!state.isUpdateModalOpen) },
                                modifier = Modifier.heightIn(min = 48.dp),
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (state.isUpdateModalOpen) AccentLime else BgControlDark,
                                    contentColor = if (state.isUpdateModalOpen) InkDark else TextPrimaryWhite
                                )
                            ) {
                                Text(
                                    text = "Update",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Bottom-Right Virtual 12-Speed Gear Shifter
                HudPod {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "GEAR",
                            color = TextMutedZinc,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                        Button(
                            onClick = { onChangeGear(-1) },
                            modifier = Modifier
                                .widthIn(min = 56.dp)
                                .heightIn(min = 56.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BgControlDark,
                                contentColor = TextPrimaryWhite
                            )
                        ) {
                            Text(text = "-", fontSize = 20.sp, fontWeight = FontWeight.Black)
                        }

                        Text(
                            text = "${state.gear} / 12",
                            style = TabularStyle,
                            color = AccentLime,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(56.dp)
                        )

                        Button(
                            onClick = { onChangeGear(1) },
                            modifier = Modifier
                                .widthIn(min = 56.dp)
                                .heightIn(min = 56.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentLime,
                                contentColor = InkDark
                            )
                        ) {
                            Text(text = "+", fontSize = 20.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraCycleButton(
    cameraMode: String,
    onCycleCamera: () -> Unit
) {
    Button(
        onClick = onCycleCamera,
        modifier = Modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = BgControlDark,
            contentColor = TextPrimaryWhite
        )
    ) {
        Text(
            text = "Cam: $cameraMode",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DemoToggleAndSlider(
    isDemoActive: Boolean,
    demoSliderEffort: Float,
    onToggleDemo: () -> Unit,
    onDemoEffortChange: (Float) -> Unit
) {
    Button(
        onClick = onToggleDemo,
        modifier = Modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isDemoActive) AccentLime else BgControlDark,
            contentColor = if (isDemoActive) InkDark else TextPrimaryWhite
        )
    ) {
        Text(
            text = if (isDemoActive) "Demo: ON" else "Demo",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }

    if (isDemoActive) {
        Column(
            modifier = Modifier
                .width(105.dp)
                .padding(horizontal = 2.dp)
        ) {
            Text(
                text = "${demoSliderEffort.toInt()} RPM",
                style = TabularStyle,
                color = TextMutedZinc,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Slider(
                value = demoSliderEffort,
                onValueChange = onDemoEffortChange,
                valueRange = 0f..130f,
                colors = SliderDefaults.colors(
                    thumbColor = AccentLime,
                    activeTrackColor = AccentLime
                )
            )
        }
    }
}

@Composable
private fun MiniGpsRouteCanvas(
    waypoints: List<GpsWaypoint>,
    lat: Double,
    lon: Double
) {
    val context = LocalContext.current
    val pts = waypoints.ifEmpty { GpsRouteEngine.presetRoutes.first().waypoints }
    val zoom = 12
    val tileX = floor(OsmTileProxy.lonToTileX(lon, zoom)).toInt()
    val tileY = floor(OsmTileProxy.latToTileY(lat, zoom)).toInt()
    var tileBitmap by remember(tileX, tileY) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(tileX, tileY) {
        val loaded = withContext(Dispatchers.IO) {
            val bytes = OsmTileProxy.fetchTileBytes(context.applicationContext, zoom, tileX, tileY)
            if (bytes != null) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } else {
                null
            }
        }
        if (loaded != null) {
            tileBitmap = loaded
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BgControlDark)
            .border(1.dp, BorderZinc, RoundedCornerShape(8.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bmp = tileBitmap
            if (bmp != null) {
                drawImage(
                    image = bmp,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
                    alpha = 0.78f
                )
                drawRect(color = Color(0x440B1510))
            } else {
                val step = 18.dp.toPx()
                var gx = step
                while (gx < size.width) {
                    drawLine(
                        color = Color(0x33A9BDB0),
                        start = Offset(gx, 0f),
                        end = Offset(gx, size.height),
                        strokeWidth = 1f
                    )
                    gx += step
                }
                var gy = step
                while (gy < size.height) {
                    drawLine(
                        color = Color(0x33A9BDB0),
                        start = Offset(0f, gy),
                        end = Offset(size.width, gy),
                        strokeWidth = 1f
                    )
                    gy += step
                }
            }

            val pad = 6.dp.toPx()
            val innerW = (size.width - pad * 2f).coerceAtLeast(1f)
            val innerH = (size.height - pad * 2f).coerceAtLeast(1f)

            if (pts.size >= 2) {
                val minLat = pts.minOf { it.lat }
                val maxLat = pts.maxOf { it.lat }
                val minLon = pts.minOf { it.lon }
                val maxLon = pts.maxOf { it.lon }
                val latSpan = (maxLat - minLat).coerceAtLeast(0.0005)
                val lonSpan = (maxLon - minLon).coerceAtLeast(0.0005)

                val path = Path()
                pts.forEachIndexed { idx, wp ->
                    val x = pad + (((wp.lon - minLon) / lonSpan) * innerW).toFloat()
                    val y = pad + ((1.0 - (wp.lat - minLat) / latSpan) * innerH).toFloat()
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = Color(0xCC0B1510),
                    style = Stroke(width = 8f, cap = StrokeCap.Round)
                )
                drawPath(
                    path = path,
                    color = AccentLime,
                    style = Stroke(width = 4.5f, cap = StrokeCap.Round)
                )

                var closestIdx = 0
                var minSq = Double.MAX_VALUE
                pts.forEachIndexed { idx, wp ->
                    val dLat = wp.lat - lat
                    val dLon = wp.lon - lon
                    val sq = dLat * dLat + dLon * dLon
                    if (sq < minSq) {
                        minSq = sq
                        closestIdx = idx
                    }
                }

                val rx = (pad + (((lon - minLon) / lonSpan) * innerW).toFloat()).coerceIn(pad, size.width - pad)
                val ry = (pad + ((1.0 - (lat - minLat) / latSpan) * innerH).toFloat()).coerceIn(pad, size.height - pad)

                val completedPath = Path()
                for (i in 0..closestIdx) {
                    val wp = pts[i]
                    val x = pad + (((wp.lon - minLon) / lonSpan) * innerW).toFloat()
                    val y = pad + ((1.0 - (wp.lat - minLat) / latSpan) * innerH).toFloat()
                    if (i == 0) completedPath.moveTo(x, y) else completedPath.lineTo(x, y)
                }
                completedPath.lineTo(rx, ry)
                drawPath(
                    path = completedPath,
                    color = SignalEmerald,
                    style = Stroke(width = 5f, cap = StrokeCap.Round)
                )

                drawCircle(
                    color = SignalEmerald.copy(alpha = 0.45f),
                    radius = 12f,
                    center = Offset(rx, ry)
                )
                drawCircle(
                    color = TextPrimaryWhite,
                    radius = 7.5f,
                    center = Offset(rx, ry)
                )
                drawCircle(
                    color = AccentLime,
                    radius = 5f,
                    center = Offset(rx, ry)
                )
            }
        }
        Text(
            text = String.format(Locale.US, "%.4f, %.4f", lat, lon),
            style = TabularStyle,
            color = TextPrimaryWhite,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color(0xAA0B1510), RoundedCornerShape(topEnd = 6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun HudPod(
    modifier: Modifier = Modifier,
    borderColor: Color = BorderZinc,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(BgCarbonHud)
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(9.dp)
    ) {
        content()
    }
}

@Composable
private fun MetricColumn(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = TextMutedZinc,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            style = TabularStyle,
            color = TextPrimaryWhite,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = unit,
            color = TextMutedZinc,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
