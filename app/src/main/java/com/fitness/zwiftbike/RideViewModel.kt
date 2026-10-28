package com.fitness.zwiftbike

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ConnectionState {
    WAITING_FOR_WATCH,
    LIVE_WATCH,
    SIGNAL_LOST,
    DEMO_MODE
}

data class PacerBot(
    val name: String,
    val wPerKg: Float,
    val speedMetersPerSec: Float,
    var distanceMeters: Float
)

data class RideUiState(
    val connectionState: ConnectionState = ConnectionState.WAITING_FOR_WATCH,
    val sensorMode: String = "ANKLE",
    val watts: Int = 0,
    val wattsPerKg: Float = 0f,
    val rpm: Int = 0,
    val bpm: Int = 0,
    val speedKmh: Float = 0f,
    val distanceKm: Float = 0f,
    val distanceMeters: Float = 0f,
    val elapsedSeconds: Int = 0,
    val caloriesKcal: Int = 0,
    val slopeGradePercent: Float = 0f,
    val elevationMeters: Float = 0f,
    val gear: Int = 7,
    val cameraMode: String = "CHASE",
    val demoSliderEffort: Float = 86f,
    val isDrafting: Boolean = false,
    val nearestBotLabel: String = "Bot C (2.5 W/kg) +12m",
    val activeRouteId: String = "alpe_dhuez",
    val activeRouteName: String = "Alpe d'Huez Climb",
    val activeRouteRegion: String = "French Alps, FR",
    val activeRoutePolylineJson: String = "[]",
    val activeRouteWaypoints: List<GpsWaypoint> = emptyList(),
    val currentLat: Double = 45.0592,
    val currentLon: Double = 6.0358,
    val currentEleMeters: Double = 724.0,
    val currentCurvature: Float = 0f,
    val recordedTrackPointsCount: Int = 0,
    val avgWatts: Int = 0,
    val avgBpm: Int = 0,
    val avgRpm: Int = 0,
    val isMapModalOpen: Boolean = false,
    val isStravaModalOpen: Boolean = false,
    val isUpdateModalOpen: Boolean = false,
    val installedVersionName: String = "v1.2.1",
    val latestReleaseTag: String = "Unchecked",
    val latestReleaseName: String = "",
    val latestPhoneApkUrl: String? = null,
    val latestWatchApkUrl: String? = null,
    val updateDownloadProgress: Int = 0,
    val updateStatusMessage: String = "Tap Check GitHub to inspect richiesamlie/LazyFitBike releases.",
    val statusBannerMessage: String = ""
)

class RideViewModel(application: Application) : AndroidViewModel(application) {

    private val physics = CyclingPhysics()
    private var activeRoute: GpsRoute = GpsRouteEngine.presetRoutes.first()

    private val recordedPoints = Collections.synchronizedList(ArrayList<RecordedTrackPoint>())
    private var lastRecordedElapsedSec: Int = -1
    private var wattsSum: Long = 0L
    private var bpmSum: Long = 0L
    private var rpmSum: Long = 0L

    private val _uiState = MutableStateFlow(
        RideUiState(
            activeRouteId = activeRoute.id,
            activeRouteName = activeRoute.name,
            activeRouteRegion = activeRoute.region,
            activeRoutePolylineJson = activeRoute.toCompactJsonArray(),
            activeRouteWaypoints = activeRoute.waypoints,
            currentLat = activeRoute.waypoints.first().lat,
            currentLon = activeRoute.waypoints.first().lon,
            currentEleMeters = activeRoute.waypoints.first().ele,
            installedVersionName = resolveInstalledVersion(application)
        )
    )
    val uiState: StateFlow<RideUiState> = _uiState.asStateFlow()

    @Volatile
    private var lastWatchPacketTimeMs: Long = 0L

    @Volatile
    private var watchRpm: Float = 0f

    @Volatile
    private var watchBpm: Int = 0

    @Volatile
    private var manualSensorModeOverride: Boolean = false

    private var elapsedAccumulatorSec: Float = 0f

    private val bots = listOf(
        PacerBot("Bot A", 1.8f, 7.2f, 40f),
        PacerBot("Bot B", 2.2f, 8.1f, 95f),
        PacerBot("Bot C", 2.5f, 8.8f, 18f),
        PacerBot("Bot D", 3.0f, 9.8f, 180f),
        PacerBot("Bot E", 3.6f, 10.9f, 310f)
    )

    private val receiver = TelemetryReceiver(application) { packet ->
        lastWatchPacketTimeMs = System.currentTimeMillis()
        watchRpm = packet.rpm
        watchBpm = packet.bpm
        val current = _uiState.value
        if (current.connectionState != ConnectionState.DEMO_MODE) {
            _uiState.value = current.copy(
                connectionState = ConnectionState.LIVE_WATCH,
                sensorMode = if (manualSensorModeOverride) current.sensorMode else packet.mode
            )
        }
    }

    private fun resolveInstalledVersion(app: Application): String {
        return runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val vName = info.versionName ?: "1.2.1"
            if (vName.startsWith("v")) vName else "v$vName"
        }.getOrDefault("v1.2.1")
    }

    init {
        receiver.start()
        startPhysicsLoop()
    }

    private fun startPhysicsLoop() {
        viewModelScope.launch {
            val dt = 0.05f
            while (isActive) {
                val nowMs = System.currentTimeMillis()
                val current = _uiState.value

                val nextConnState = when (current.connectionState) {
                    ConnectionState.LIVE_WATCH -> {
                        if (nowMs - lastWatchPacketTimeMs > 3000L) {
                            ConnectionState.SIGNAL_LOST
                        } else {
                            ConnectionState.LIVE_WATCH
                        }
                    }
                    else -> current.connectionState
                }

                val inputRpm: Float
                val inputBpm: Int
                when (nextConnState) {
                    ConnectionState.DEMO_MODE -> {
                        inputRpm = current.demoSliderEffort
                        inputBpm = if (current.demoSliderEffort < 5f) {
                            68
                        } else {
                            (85f + current.demoSliderEffort * 0.72f).roundToInt().coerceIn(68, 192)
                        }
                    }
                    ConnectionState.LIVE_WATCH -> {
                        inputRpm = watchRpm
                        inputBpm = if (watchBpm > 0) watchBpm else {
                            if (watchRpm > 5f) (92f + watchRpm * 0.55f).roundToInt() else 70
                        }
                    }
                    ConnectionState.WAITING_FOR_WATCH,
                    ConnectionState.SIGNAL_LOST -> {
                        inputRpm = 0f
                        inputBpm = 0
                    }
                }

                val routeTotal = activeRoute.totalDistanceMeters.coerceAtLeast(100f)
                var closestDelta = Float.MAX_VALUE
                var closestBot = bots[2]
                var draftingNow = false

                for (bot in bots) {
                    bot.distanceMeters = (bot.distanceMeters + bot.speedMetersPerSec * dt) % routeTotal
                    val riderLoopDist = current.distanceMeters % routeTotal
                    var delta = bot.distanceMeters - riderLoopDist
                    if (delta < -routeTotal / 2f) delta += routeTotal
                    if (delta > routeTotal / 2f) delta -= routeTotal

                    if (abs(delta) < abs(closestDelta)) {
                        closestDelta = delta
                        closestBot = bot
                    }
                    if (delta in 1.0f..8.5f && current.speedKmh > 12f) {
                        draftingNow = true
                    }
                }

                val gpsNow = GpsRouteEngine.interpolateAtDistance(activeRoute, physics.totalDistanceMeters)

                val stepResult = physics.step(
                    dtSeconds = dt,
                    mode = current.sensorMode,
                    rpm = inputRpm,
                    bpm = inputBpm,
                    gear = current.gear,
                    isDrafting = draftingNow,
                    overrideGradePercent = gpsNow.gradePercent
                )

                val gpsAfterStep = GpsRouteEngine.interpolateAtDistance(activeRoute, stepResult.distanceMeters)

                if (stepResult.speedKmh > 0.5f) {
                    elapsedAccumulatorSec += dt
                    val wholeSec = elapsedAccumulatorSec.toInt()
                    if (wholeSec > lastRecordedElapsedSec) {
                        lastRecordedElapsedSec = wholeSec
                        val wInt = stepResult.watts.roundToInt()
                        val rInt = stepResult.effectiveRpm.roundToInt()
                        recordedPoints.add(
                            RecordedTrackPoint(
                                lat = gpsAfterStep.lat,
                                lon = gpsAfterStep.lon,
                                eleMeters = gpsAfterStep.ele,
                                timestampEpochMs = nowMs,
                                watts = wInt,
                                rpm = rInt,
                                bpm = inputBpm
                            )
                        )
                        wattsSum += wInt
                        bpmSum += inputBpm
                        rpmSum += rInt
                    }
                }

                val count = recordedPoints.size
                val avgW = if (count > 0) (wattsSum / count).toInt() else 0
                val avgB = if (count > 0) (bpmSum / count).toInt() else 0
                val avgR = if (count > 0) (rpmSum / count).toInt() else 0

                val signPrefix = if (closestDelta >= 0f) "+" else ""
                val botLabel = buildString {
                    append("${closestBot.name} (${closestBot.wPerKg} W/kg) ")
                    append("$signPrefix${closestDelta.roundToInt()}m")
                    if (draftingNow) append(" [DRAFT]")
                }

                _uiState.value = current.copy(
                    connectionState = nextConnState,
                    watts = stepResult.watts.roundToInt(),
                    wattsPerKg = stepResult.wattsPerKg,
                    rpm = stepResult.effectiveRpm.roundToInt(),
                    bpm = inputBpm,
                    speedKmh = stepResult.speedKmh,
                    distanceKm = stepResult.distanceMeters / 1000f,
                    distanceMeters = stepResult.distanceMeters,
                    elapsedSeconds = elapsedAccumulatorSec.toInt(),
                    caloriesKcal = stepResult.caloriesKcal.roundToInt(),
                    slopeGradePercent = stepResult.slopeGradePercent,
                    elevationMeters = stepResult.elevationMeters,
                    isDrafting = draftingNow,
                    nearestBotLabel = botLabel,
                    currentLat = gpsAfterStep.lat,
                    currentLon = gpsAfterStep.lon,
                    currentEleMeters = gpsAfterStep.ele,
                    currentCurvature = gpsAfterStep.curvatureNormalized,
                    recordedTrackPointsCount = count,
                    avgWatts = avgW,
                    avgBpm = avgB,
                    avgRpm = avgR
                )

                delay(50L)
            }
        }
    }

    fun selectPresetRoute(routeId: String) {
        val route = GpsRouteEngine.getPresetById(routeId)
        applyNewRoute(route, "Loaded preset route: ${route.name}")
    }

    fun importGpxRouteXml(gpxXml: String) {
        val parsed = GpsRouteEngine.parseGpxRoute(gpxXml)
        if (parsed != null) {
            applyNewRoute(parsed, "Imported GPX route: ${parsed.name} (${parsed.waypoints.size} pts)")
        } else {
            _uiState.value = _uiState.value.copy(
                statusBannerMessage = "Could not parse GPX file: ensure it contains <trkpt lat lon> tags."
            )
        }
    }

    fun requestCustomOsrmRoute(startLat: Double, startLon: Double, endLat: Double, endLon: Double) {
        _uiState.value = _uiState.value.copy(
            statusBannerMessage = "Fetching online road route from OSRM..."
        )
        viewModelScope.launch {
            val osrmRoute = GpsRouteEngine.fetchOsrmRoute(startLat, startLon, endLat, endLon)
            if (osrmRoute != null) {
                applyNewRoute(osrmRoute, "Loaded OSRM road route (${String.format("%.1f", osrmRoute.totalDistanceMeters / 1000f)} km)")
            } else {
                val direct = GpsRouteEngine.buildRouteFromRawPoints(
                    id = "custom_direct_${System.currentTimeMillis()}",
                    name = "Custom Pin Route",
                    region = "Map Selection",
                    rawPoints = listOf(
                        Triple(startLat, startLon, 110.0),
                        Triple((startLat + endLat) * 0.5 + 0.002, (startLon + endLon) * 0.5, 145.0),
                        Triple(endLat, endLon, 125.0)
                    )
                )
                applyNewRoute(direct, "Created custom map route (${String.format("%.1f", direct.totalDistanceMeters / 1000f)} km)")
            }
        }
    }

    private fun applyNewRoute(route: GpsRoute, statusMsg: String) {
        activeRoute = route
        physics.reset()
        recordedPoints.clear()
        lastRecordedElapsedSec = -1
        elapsedAccumulatorSec = 0f
        wattsSum = 0L
        bpmSum = 0L
        rpmSum = 0L
        val firstWp = route.waypoints.first()
        _uiState.value = _uiState.value.copy(
            activeRouteId = route.id,
            activeRouteName = route.name,
            activeRouteRegion = route.region,
            activeRoutePolylineJson = route.toCompactJsonArray(),
            activeRouteWaypoints = route.waypoints,
            currentLat = firstWp.lat,
            currentLon = firstWp.lon,
            currentEleMeters = firstWp.ele,
            recordedTrackPointsCount = 0,
            statusBannerMessage = statusMsg
        )
    }

    fun setMapModalOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(
            isMapModalOpen = open,
            isStravaModalOpen = false,
            isUpdateModalOpen = false
        )
    }

    fun setStravaModalOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(
            isStravaModalOpen = open,
            isMapModalOpen = false,
            isUpdateModalOpen = false
        )
    }

    fun setUpdateModalOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(
            isUpdateModalOpen = open,
            isMapModalOpen = false,
            isStravaModalOpen = false
        )
        if (open && _uiState.value.latestReleaseTag == "Unchecked") {
            checkGithubUpdates()
        }
    }

    fun checkGithubUpdates() {
        _uiState.value = _uiState.value.copy(
            updateStatusMessage = "Checking GitHub API (richiesamlie/LazyFitBike)..."
        )
        viewModelScope.launch {
            val info = GithubUpdater.fetchLatestRelease()
            if (info != null) {
                val hasPhone = info.phoneApkUrl != null
                val hasWatch = info.watchApkUrl != null
                _uiState.value = _uiState.value.copy(
                    latestReleaseTag = info.tagName,
                    latestReleaseName = info.releaseName,
                    latestPhoneApkUrl = info.phoneApkUrl,
                    latestWatchApkUrl = info.watchApkUrl,
                    updateStatusMessage = "Found ${info.tagName} (Phone APK: $hasPhone, Watch APK: $hasWatch)"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    updateStatusMessage = "No release found yet or offline. Push a release to richiesamlie/LazyFitBike."
                )
            }
        }
    }

    fun downloadAndInstallPhoneUpdate(context: Context) {
        viewModelScope.launch {
            var phoneUrl = _uiState.value.latestPhoneApkUrl
            if (phoneUrl.isNullOrEmpty()) {
                val info = GithubUpdater.fetchLatestRelease()
                phoneUrl = info?.phoneApkUrl
                if (info != null) {
                    _uiState.value = _uiState.value.copy(
                        latestReleaseTag = info.tagName,
                        latestPhoneApkUrl = info.phoneApkUrl,
                        latestWatchApkUrl = info.watchApkUrl
                    )
                }
            }
            if (phoneUrl.isNullOrEmpty()) {
                _uiState.value = _uiState.value.copy(
                    updateStatusMessage = "No LazyFitBike-phone.apk asset found in latest GitHub release."
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                updateDownloadProgress = 0,
                updateStatusMessage = "Downloading LazyFitBike-phone.apk from GitHub..."
            )
            val file = GithubUpdater.downloadApkToFile(
                context = context,
                apkUrl = phoneUrl,
                targetFileName = "LazyFitBike-phone.apk",
                onProgress = { pct ->
                    _uiState.value = _uiState.value.copy(updateDownloadProgress = pct)
                }
            )
            if (file != null) {
                val installMsg = GithubUpdater.promptInstallApk(context, file)
                _uiState.value = _uiState.value.copy(
                    updateDownloadProgress = 100,
                    updateStatusMessage = installMsg
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    updateStatusMessage = "Failed to download Phone APK from GitHub."
                )
            }
        }
    }

    fun downloadAndRelayWatchUpdate(context: Context) {
        viewModelScope.launch {
            var watchUrl = _uiState.value.latestWatchApkUrl
            if (watchUrl.isNullOrEmpty()) {
                val info = GithubUpdater.fetchLatestRelease()
                watchUrl = info?.watchApkUrl
                if (info != null) {
                    _uiState.value = _uiState.value.copy(
                        latestReleaseTag = info.tagName,
                        latestPhoneApkUrl = info.phoneApkUrl,
                        latestWatchApkUrl = info.watchApkUrl
                    )
                }
            }
            if (watchUrl.isNullOrEmpty()) {
                _uiState.value = _uiState.value.copy(
                    updateStatusMessage = "No LazyFitBike-watch.apk asset found in latest GitHub release."
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                updateDownloadProgress = 0,
                updateStatusMessage = "Downloading LazyFitBike-watch.apk from GitHub..."
            )
            val file = GithubUpdater.downloadApkToFile(
                context = context,
                apkUrl = watchUrl,
                targetFileName = "LazyFitBike-watch.apk",
                onProgress = { pct ->
                    _uiState.value = _uiState.value.copy(updateDownloadProgress = pct)
                }
            )
            if (file != null) {
                _uiState.value = _uiState.value.copy(
                    updateStatusMessage = "Streaming Watch APK over Bluetooth ChannelClient..."
                )
                val relayMsg = GithubUpdater.pushWatchApkOverBluetooth(context, file)
                _uiState.value = _uiState.value.copy(
                    updateDownloadProgress = 100,
                    updateStatusMessage = relayMsg
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    updateStatusMessage = "Failed to download Watch APK from GitHub."
                )
            }
        }
    }

    fun exportAndShareStravaGpx(context: Context) {
        val snapshot = ensureNonEmptyTrackPoints()
        runCatching {
            val file = StravaSyncManager.exportAndShareGpx(
                context = context,
                routeName = activeRoute.name,
                trackPoints = snapshot
            )
            _uiState.value = _uiState.value.copy(
                statusBannerMessage = "Exported ${snapshot.size} GPS+HR+Watts points to ${file.name}"
            )
        }.onFailure { err ->
            _uiState.value = _uiState.value.copy(
                statusBannerMessage = "Export failed: ${err.message}"
            )
        }
    }

    fun uploadDirectToStrava(accessToken: String) {
        val snapshot = ensureNonEmptyTrackPoints()
        _uiState.value = _uiState.value.copy(
            statusBannerMessage = "Uploading ${snapshot.size} GPS trackpoints to Strava API..."
        )
        viewModelScope.launch {
            val resultMsg = StravaSyncManager.uploadToStravaApi(
                accessToken = accessToken,
                routeName = activeRoute.name,
                trackPoints = snapshot
            )
            _uiState.value = _uiState.value.copy(statusBannerMessage = resultMsg)
        }
    }

    private fun ensureNonEmptyTrackPoints(): List<RecordedTrackPoint> {
        synchronized(recordedPoints) {
            if (recordedPoints.isNotEmpty()) {
                return ArrayList(recordedPoints)
            }
        }
        val baseNow = System.currentTimeMillis() - 15_000L
        return (0..14).map { sec ->
            val pt = GpsRouteEngine.interpolateAtDistance(activeRoute, sec * 7.5f)
            RecordedTrackPoint(
                lat = pt.lat,
                lon = pt.lon,
                eleMeters = pt.ele,
                timestampEpochMs = baseNow + sec * 1000L,
                watts = 195,
                rpm = 86,
                bpm = 144
            )
        }
    }

    fun toggleSensorMode() {
        manualSensorModeOverride = true
        val next = if (_uiState.value.sensorMode == "ANKLE") "WRIST" else "ANKLE"
        _uiState.value = _uiState.value.copy(sensorMode = next)
    }

    fun changeGear(delta: Int) {
        val nextGear = (_uiState.value.gear + delta).coerceIn(1, 12)
        _uiState.value = _uiState.value.copy(gear = nextGear)
    }

    fun cycleCameraMode() {
        val nextCam = when (_uiState.value.cameraMode) {
            "CHASE" -> "POV"
            "POV" -> "DRONE"
            else -> "CHASE"
        }
        _uiState.value = _uiState.value.copy(cameraMode = nextCam)
    }

    fun toggleDemoMode() {
        val current = _uiState.value
        val nextState = if (current.connectionState == ConnectionState.DEMO_MODE) {
            if (System.currentTimeMillis() - lastWatchPacketTimeMs <= 3000L) {
                ConnectionState.LIVE_WATCH
            } else {
                ConnectionState.WAITING_FOR_WATCH
            }
        } else {
            ConnectionState.DEMO_MODE
        }
        _uiState.value = current.copy(connectionState = nextState)
    }

    fun setDemoEffort(effort: Float) {
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionState.DEMO_MODE,
            demoSliderEffort = effort.coerceIn(0f, 130f)
        )
    }

    override fun onCleared() {
        receiver.stop()
        super.onCleared()
    }
}
