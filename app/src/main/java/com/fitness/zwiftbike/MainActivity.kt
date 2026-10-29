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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
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

            VeloPulseCockpitScreen(
                state = state,
                onToggleSensorMode = rideViewModel::toggleSensorMode,
                onChangeGear = rideViewModel::changeGear,
                onCycleCamera = rideViewModel::cycleCameraMode,
                onToggleDemo = rideViewModel::toggleDemoMode,
                onDemoEffortChange = rideViewModel::setDemoEffort,
                onSelectPresetRoute = rideViewModel::selectPresetRoute,
                onOpenGpxPicker = { gpxLauncher.launch("*/*") },
                onRequestOsrmRoute = rideViewModel::requestCustomOsrmRoute,
                onSetMapModalOpen = rideViewModel::setMapModalOpen,
                onSetStravaModalOpen = rideViewModel::setStravaModalOpen,
                onSetUpdateModalOpen = rideViewModel::setUpdateModalOpen,
                onCheckGithubUpdates = rideViewModel::checkGithubUpdates,
                onDownloadPhoneUpdate = { rideViewModel.downloadAndInstallPhoneUpdate(context) },
                onRelayWatchUpdate = { rideViewModel.downloadAndRelayWatchUpdate(context) },
                onShareWatchApk = { rideViewModel.shareWatchUpdateApk(context) },
                onExportStravaGpx = { rideViewModel.exportAndShareStravaGpx(context) },
                onUploadDirectToStrava = rideViewModel::uploadDirectToStrava
            )
        }
    }
}

private class MapJsBridge(
    private val onRouteRequested: (Double, Double, Double, Double) -> Unit
) {
    @JavascriptInterface
    fun onMapRouteRequested(startLat: Double, startLon: Double, endLat: Double, endLon: Double) {
        onRouteRequested(startLat, startLon, endLat, endLon)
    }
}

private object OsmTileProxy {
    private const val USER_AGENT = "LazyFitBike/1.3.3 (https://github.com/richiesamlie/LazyFitBike)"
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
        if (!url.contains("tile.openstreetmap.org") && !url.contains("basemaps.cartocdn.com")) {
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
        val cacheDir = File(context.cacheDir, "osm_tiles")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val cacheFile = File(cacheDir, "${z}_${x}_${y}.png")
        if (cacheFile.exists() && cacheFile.length() > 256L) {
            runCatching { return cacheFile.readBytes() }
        }

        val candidateUrls = listOf(
            "https://tile.openstreetmap.org/$z/$x/$y.png",
            "https://a.basemaps.cartocdn.com/rastertiles/voyager/$z/$x/$y.png"
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

private val BgCarbonHud = Color(0xE609090B)
private val BgControlDark = Color(0xFF18181B)
private val BorderZinc = Color(0xFF27272A)
private val TextPrimaryWhite = Color(0xFFF4F4F5)
private val TextMutedZinc = Color(0xFFA1A1AA)
private val AccentOrange = Color(0xFFF97316)
private val SignalRose = Color(0xFFFB7185)
private val SignalEmerald = Color(0xFF34D399)
private val SignalAmber = Color(0xFFFBBF24)

private val TabularStyle = TextStyle(fontFeatureSettings = "tnum")

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
        "if(window.updateRideState){window.updateRideState(%.2f,%d,%d,%.2f,%.2f,'%s',%.6f,%.6f,%.3f,%b);}",
        state.speedKmh,
        state.rpm,
        state.watts,
        state.slopeGradePercent,
        state.distanceMeters,
        state.cameraMode,
        state.currentLat,
        state.currentLon,
        state.currentCurvature,
        state.isMapModalOpen
    )
    view?.evaluateJavascript(jsCall, null)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VeloPulseCockpitScreen(
    state: RideUiState,
    onToggleSensorMode: () -> Unit,
    onChangeGear: (Int) -> Unit,
    onCycleCamera: () -> Unit,
    onToggleDemo: () -> Unit,
    onDemoEffortChange: (Float) -> Unit,
    onSelectPresetRoute: (String) -> Unit,
    onOpenGpxPicker: () -> Unit,
    onRequestOsrmRoute: (Double, Double, Double, Double) -> Unit,
    onSetMapModalOpen: (Boolean) -> Unit,
    onSetStravaModalOpen: (Boolean) -> Unit,
    onSetUpdateModalOpen: (Boolean) -> Unit,
    onCheckGithubUpdates: () -> Unit,
    onDownloadPhoneUpdate: () -> Unit,
    onRelayWatchUpdate: () -> Unit,
    onShareWatchApk: () -> Unit,
    onExportStravaGpx: () -> Unit,
    onUploadDirectToStrava: (String) -> Unit
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var stravaAccessToken by remember { mutableStateOf("") }
    val latestState by rememberUpdatedState(state)

    LaunchedEffect(state.activeRouteId, state.activeRoutePolylineJson) {
        pushRoutePolylineToWebView(webViewRef, state.activeRoutePolylineJson)
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
        state.isMapModalOpen
    ) {
        pushRideStateToWebView(webViewRef, state)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF09090B))
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
                    settings.userAgentString = "LazyFitBike/1.3.3 (https://github.com/richiesamlie/LazyFitBike)"
                    addJavascriptInterface(MapJsBridge(onRequestOsrmRoute), "AndroidBridge")
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
            // TOP PERIMETER ROW
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // ZONE A: Primary Effort Pod
                    HudPod(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Column {
                                Text(
                                    text = "POWER (EST)",
                                    color = TextMutedZinc,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = state.watts.toString(),
                                        style = TabularStyle,
                                        color = AccentOrange,
                                        fontSize = 26.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "W",
                                        color = TextMutedZinc,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "W / KG",
                                    color = TextMutedZinc,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = String.format(Locale.US, "%.1f", state.wattsPerKg),
                                    style = TabularStyle,
                                    color = TextPrimaryWhite,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 5.dp),
                            color = BorderZinc
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "CADENCE",
                                    color = TextMutedZinc,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${state.rpm} RPM",
                                    style = TabularStyle,
                                    color = TextPrimaryWhite,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "HEART RATE",
                                    color = TextMutedZinc,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                val bpmText = if (state.bpm > 0) "${state.bpm} BPM" else "READY"
                                Text(
                                    text = bpmText,
                                    style = TabularStyle,
                                    color = SignalRose,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // ZONE B: Route Progress Pod
                    HudPod(modifier = Modifier.weight(1.1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            MetricColumn(
                                label = "SPEED",
                                value = String.format(Locale.US, "%.1f", state.speedKmh),
                                unit = "km/h"
                            )
                            MetricColumn(
                                label = "DIST",
                                value = String.format(Locale.US, "%.2f", state.distanceKm),
                                unit = "km"
                            )
                            MetricColumn(
                                label = "TIME",
                                value = formatElapsedTime(state.elapsedSeconds),
                                unit = "min"
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = BorderZinc
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = state.activeRouteName,
                                color = TextMutedZinc,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "REC ${state.recordedTrackPointsCount} pts",
                                style = TabularStyle,
                                color = SignalEmerald,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // ZONE C: Corner Live GPS Mini-Map + Grade
                    HudPod(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSetMapModalOpen(!state.isMapModalOpen) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val dotColor = when (state.connectionState) {
                                    ConnectionState.LIVE_WATCH -> SignalEmerald
                                    ConnectionState.DEMO_MODE -> AccentOrange
                                    ConnectionState.WAITING_FOR_WATCH,
                                    ConnectionState.SIGNAL_LOST -> SignalAmber
                                }
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "GPS MAP (TAP)",
                                    color = TextPrimaryWhite,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            val gradeSign = if (state.slopeGradePercent >= 0f) "+" else ""
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%s%.1f%%",
                                    gradeSign,
                                    state.slopeGradePercent
                                ),
                                style = TabularStyle,
                                color = SignalAmber,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        Spacer(modifier = Modifier.height(5.dp))

                        MiniGpsRouteCanvas(
                            waypoints = state.activeRouteWaypoints,
                            lat = state.currentLat,
                            lon = state.currentLon
                        )
                    }
                }

                if (state.connectionState == ConnectionState.WAITING_FOR_WATCH ||
                    state.connectionState == ConnectionState.SIGNAL_LOST
                ) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(10.dp))
                            .background(BgCarbonHud)
                            .border(1.dp, SignalAmber, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
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
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentOrange,
                                contentColor = TextPrimaryWhite
                            )
                        ) {
                            Text(
                                text = "Enable Demo Slider",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
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
                                color = AccentOrange,
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

                        GpsRouteEngine.presetRoutes.forEach { preset ->
                            val isSelected = preset.id == state.activeRouteId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0x33F97316) else BgControlDark)
                                    .border(
                                        1.dp,
                                        if (isSelected) AccentOrange else BorderZinc,
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
                                        color = if (isSelected) AccentOrange else TextPrimaryWhite,
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

                        Spacer(modifier = Modifier.height(6.dp))

                        Button(
                            onClick = onOpenGpxPicker,
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
                                text = "Import .GPX Route File",
                                fontSize = 12.sp,
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
                                color = AccentOrange,
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
                                containerColor = AccentOrange,
                                contentColor = TextPrimaryWhite
                            )
                        ) {
                            Text(
                                text = "1. Export & Share .GPX File (GPS + HR + Cadence + Watts)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
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
                                    color = AccentOrange,
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
                            color = AccentOrange,
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
                                    containerColor = if (isPhoneUpToDate) BgControlDark else AccentOrange,
                                    contentColor = if (isPhoneUpToDate) SignalEmerald else TextPrimaryWhite
                                )
                            ) {
                                Text(
                                    text = if (isPhoneUpToDate) "Up to Date (${state.installedVersionName})" else "Update Phone APK",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
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
                                contentColor = AccentOrange
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

            // BOTTOM PERIMETER ROW
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                HudPod(modifier = Modifier.weight(1f, fill = false)) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = onToggleSensorMode,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BgControlDark,
                                contentColor = TextPrimaryWhite
                            )
                        ) {
                            Text(
                                text = "Mode: ${state.sensorMode}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = onCycleCamera,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BgControlDark,
                                contentColor = TextPrimaryWhite
                            )
                        ) {
                            Text(
                                text = "Cam: ${state.cameraMode}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = { onSetMapModalOpen(!state.isMapModalOpen) },
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.isMapModalOpen) AccentOrange else BgControlDark,
                                contentColor = TextPrimaryWhite
                            )
                        ) {
                            Text(
                                text = "Routes & Map",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = { onSetStravaModalOpen(!state.isStravaModalOpen) },
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.isStravaModalOpen) AccentOrange else BgControlDark,
                                contentColor = TextPrimaryWhite
                            )
                        ) {
                            Text(
                                text = "Strava",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = { onSetUpdateModalOpen(!state.isUpdateModalOpen) },
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.isUpdateModalOpen) AccentOrange else BgControlDark,
                                contentColor = TextPrimaryWhite
                            )
                        ) {
                            Text(
                                text = "Update",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = onToggleDemo,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.connectionState == ConnectionState.DEMO_MODE) {
                                    AccentOrange
                                } else {
                                    BgControlDark
                                },
                                contentColor = TextPrimaryWhite
                            )
                        ) {
                            Text(
                                text = if (state.connectionState == ConnectionState.DEMO_MODE) "Demo: ON" else "Demo",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (state.connectionState == ConnectionState.DEMO_MODE) {
                            Column(
                                modifier = Modifier
                                    .width(105.dp)
                                    .padding(horizontal = 2.dp)
                            ) {
                                Text(
                                    text = "${state.demoSliderEffort.toInt()} RPM",
                                    style = TabularStyle,
                                    color = TextMutedZinc,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Slider(
                                    value = state.demoSliderEffort,
                                    onValueChange = onDemoEffortChange,
                                    valueRange = 0f..130f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = AccentOrange,
                                        activeTrackColor = AccentOrange
                                    )
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
                                .widthIn(min = 48.dp)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BgControlDark,
                                contentColor = TextPrimaryWhite
                            )
                        ) {
                            Text(text = "-", fontSize = 18.sp, fontWeight = FontWeight.Black)
                        }

                        Text(
                            text = "${state.gear} / 12",
                            style = TabularStyle,
                            color = AccentOrange,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(52.dp)
                        )

                        Button(
                            onClick = { onChangeGear(1) },
                            modifier = Modifier
                                .widthIn(min = 48.dp)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentOrange,
                                contentColor = TextPrimaryWhite
                            )
                        ) {
                            Text(text = "+", fontSize = 18.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
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
            .background(Color(0xFF1E293B))
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
                drawRect(color = Color(0x4409090B))
            } else {
                val step = 18.dp.toPx()
                var gx = step
                while (gx < size.width) {
                    drawLine(
                        color = Color(0x3394A3B8),
                        start = Offset(gx, 0f),
                        end = Offset(gx, size.height),
                        strokeWidth = 1f
                    )
                    gx += step
                }
                var gy = step
                while (gy < size.height) {
                    drawLine(
                        color = Color(0x3394A3B8),
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
                    color = Color(0xCC09090B),
                    style = Stroke(width = 8f, cap = StrokeCap.Round)
                )
                drawPath(
                    path = path,
                    color = AccentOrange,
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
                    color = Color.White,
                    radius = 7.5f,
                    center = Offset(rx, ry)
                )
                drawCircle(
                    color = AccentOrange,
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
                .background(Color(0xAA09090B), RoundedCornerShape(topEnd = 6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun HudPod(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(BgCarbonHud)
            .border(1.dp, BorderZinc, RoundedCornerShape(12.dp))
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

private fun formatElapsedTime(totalSeconds: Int): String {
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
