package com.fitness.zwiftbike

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class GpsWaypoint(
    val lat: Double,
    val lon: Double,
    val ele: Double,
    val cumulativeDistMeters: Float,
    val gradePercent: Float,
    val bearingDeg: Float
)

data class GpsRoute(
    val id: String,
    val name: String,
    val region: String,
    val totalDistanceMeters: Float,
    val totalElevationGainMeters: Float,
    val waypoints: List<GpsWaypoint>
) {
    fun toCompactJsonArray(): String {
        val arr = JSONArray()
        for (wp in waypoints) {
            val pt = JSONArray()
            pt.put(wp.lat)
            pt.put(wp.lon)
            pt.put(wp.ele)
            arr.put(pt)
        }
        return arr.toString()
    }
}

data class InterpolatedGpsPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double,
    val gradePercent: Float,
    val bearingDeg: Float,
    val curvatureNormalized: Float
)

/**
 * Real-world GPS route engine providing preset iconic routes, GPX XML import,
 * OSRM online road routing, and distance-to-coordinate interpolation.
 */
object GpsRouteEngine {

    val presetRoutes: List<GpsRoute> by lazy {
        listOf(
            buildAlpeDhuezRoute(),
            buildCentralParkRoute(),
            buildMarinaBayRoute(),
            buildKintamaniBaliRoute()
        )
    }

    fun getPresetById(id: String): GpsRoute {
        return presetRoutes.firstOrNull { it.id == id } ?: presetRoutes.first()
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2.0) * sin(dLon / 2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return r * c
    }

    fun computeBearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        val theta = Math.toDegrees(atan2(y, x))
        return (((theta + 360.0) % 360.0)).toFloat()
    }

    fun buildRouteFromRawPoints(
        id: String,
        name: String,
        region: String,
        rawPoints: List<Triple<Double, Double, Double>>
    ): GpsRoute {
        if (rawPoints.size < 2) {
            val fallback = rawPoints.firstOrNull() ?: Triple(45.0592, 6.0358, 724.0)
            val wp = GpsWaypoint(fallback.first, fallback.second, fallback.third, 0f, 0f, 0f)
            return GpsRoute(id, name, region, 100f, 0f, listOf(wp, wp.copy(cumulativeDistMeters = 100f)))
        }

        // Densify segments longer than 40 meters for smooth 3D road and Strava 1 Hz tracking
        val densified = ArrayList<Triple<Double, Double, Double>>()
        for (i in 0 until rawPoints.size - 1) {
            val p0 = rawPoints[i]
            val p1 = rawPoints[i + 1]
            densified.add(p0)
            val segDist = haversineMeters(p0.first, p0.second, p1.first, p1.second)
            if (segDist > 40.0) {
                val steps = (segDist / 30.0).roundToInt().coerceIn(2, 60)
                for (s in 1 until steps) {
                    val t = s.toDouble() / steps.toDouble()
                    densified.add(
                        Triple(
                            p0.first + (p1.first - p0.first) * t,
                            p0.second + (p1.second - p0.second) * t,
                            p0.third + (p1.third - p0.third) * t
                        )
                    )
                }
            }
        }
        densified.add(rawPoints.last())

        val waypoints = ArrayList<GpsWaypoint>(densified.size)
        var cumDist = 0f
        var elevGain = 0f

        for (i in densified.indices) {
            val curr = densified[i]
            if (i == 0) {
                val next = densified[1]
                val dist = max(1.0, haversineMeters(curr.first, curr.second, next.first, next.second))
                val grade = (((next.third - curr.third) / dist) * 100.0).toFloat().coerceIn(-18f, 18f)
                val bearing = computeBearingDeg(curr.first, curr.second, next.first, next.second)
                waypoints.add(GpsWaypoint(curr.first, curr.second, curr.third, 0f, grade, bearing))
            } else {
                val prev = densified[i - 1]
                val segDist = haversineMeters(prev.first, prev.second, curr.first, curr.second).toFloat()
                cumDist += segDist
                val dEle = (curr.third - prev.third).toFloat()
                if (dEle > 0f) elevGain += dEle
                val grade = if (segDist > 0.5f) {
                    ((dEle / segDist) * 100f).coerceIn(-18f, 18f)
                } else {
                    waypoints.last().gradePercent
                }
                val bearing = computeBearingDeg(prev.first, prev.second, curr.first, curr.second)
                waypoints.add(
                    GpsWaypoint(
                        lat = curr.first,
                        lon = curr.second,
                        ele = curr.third,
                        cumulativeDistMeters = cumDist,
                        gradePercent = grade,
                        bearingDeg = bearing
                    )
                )
            }
        }

        return GpsRoute(
            id = id,
            name = name,
            region = region,
            totalDistanceMeters = max(1f, cumDist),
            totalElevationGainMeters = elevGain,
            waypoints = waypoints
        )
    }

    fun interpolateAtDistance(route: GpsRoute, distanceMeters: Float): InterpolatedGpsPoint {
        val pts = route.waypoints
        if (pts.isEmpty()) {
            return InterpolatedGpsPoint(45.0592, 6.0358, 724.0, 0f, 0f, 0f)
        }
        if (pts.size == 1) {
            val p = pts.first()
            return InterpolatedGpsPoint(p.lat, p.lon, p.ele, p.gradePercent, p.bearingDeg, 0f)
        }

        val total = max(1f, route.totalDistanceMeters)
        val wrappedDist = ((distanceMeters % total) + total) % total

        var idx = 0
        for (i in 0 until pts.size - 1) {
            if (wrappedDist >= pts[i].cumulativeDistMeters && wrappedDist <= pts[i + 1].cumulativeDistMeters) {
                idx = i
                break
            }
        }

        val w0 = pts[idx]
        val w1 = pts[(idx + 1).coerceAtMost(pts.size - 1)]
        val wNext = pts[(idx + 2).coerceAtMost(pts.size - 1)]

        val segLen = max(0.001f, w1.cumulativeDistMeters - w0.cumulativeDistMeters)
        val t = ((wrappedDist - w0.cumulativeDistMeters) / segLen).coerceIn(0f, 1f).toDouble()

        val lat = w0.lat + (w1.lat - w0.lat) * t
        val lon = w0.lon + (w1.lon - w0.lon) * t
        val ele = w0.ele + (w1.ele - w0.ele) * t
        val grade = w0.gradePercent + (w1.gradePercent - w0.gradePercent) * t.toFloat()

        // Compute normalized road curvature from bearing delta to steer the 3D road
        var deltaBearing = wNext.bearingDeg - w0.bearingDeg
        while (deltaBearing > 180f) deltaBearing -= 360f
        while (deltaBearing < -180f) deltaBearing += 360f
        val curvature = (deltaBearing / 45f).coerceIn(-1.5f, 1.5f)

        return InterpolatedGpsPoint(
            lat = lat,
            lon = lon,
            ele = ele,
            gradePercent = grade,
            bearingDeg = w1.bearingDeg,
            curvatureNormalized = curvature
        )
    }

    /**
     * Parses a standard GPX 1.1 XML string into a rideable GpsRoute.
     */
    fun parseGpxRoute(gpxXml: String, customName: String = "Imported GPX Route"): GpsRoute? {
        val trkptRegex = Regex(
            """<trkpt[^>]*lat=["']([0-9.+\-]+)["'][^>]*lon=["']([0-9.+\-]+)["'][^>]*>(.*?)</trkpt>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val eleRegex = Regex("""<ele>([0-9.+\-]+)</ele>""", RegexOption.IGNORE_CASE)
        val nameRegex = Regex("""<name>([^<]+)</name>""", RegexOption.IGNORE_CASE)

        val extractedName = nameRegex.find(gpxXml)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: customName

        val rawPoints = ArrayList<Triple<Double, Double, Double>>()
        for (match in trkptRegex.findAll(gpxXml)) {
            val lat = match.groupValues[1].toDoubleOrNull() ?: continue
            val lon = match.groupValues[2].toDoubleOrNull() ?: continue
            val body = match.groupValues[3]
            val ele = eleRegex.find(body)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 50.0
            rawPoints.add(Triple(lat, lon, ele))
        }

        if (rawPoints.size < 2) return null
        return buildRouteFromRawPoints(
            id = "gpx_${System.currentTimeMillis()}",
            name = extractedName,
            region = "GPX Import",
            rawPoints = rawPoints
        )
    }

    /**
     * Queries the public OSRM road routing server to build a real road route between two map taps.
     */
    suspend fun fetchOsrmRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ): GpsRoute? = withContext(Dispatchers.IO) {
        runCatching {
            val urlStr = String.format(
                Locale.US,
                "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
                startLon,
                startLat,
                endLon,
                endLat
            )
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "VeloPulse-Android/2.0")
            }
            if (conn.responseCode !in 200..299) return@withContext null
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(responseText)
            val routes = root.optJSONArray("routes") ?: return@withContext null
            if (routes.length() == 0) return@withContext null
            val geometry = routes.getJSONObject(0).getJSONObject("geometry")
            val coords = geometry.getJSONArray("coordinates")

            val rawPoints = ArrayList<Triple<Double, Double, Double>>(coords.length())
            for (i in 0 until coords.length()) {
                val pt = coords.getJSONArray(i)
                val lon = pt.getDouble(0)
                val lat = pt.getDouble(1)
                // Synthesize gentle rolling road elevation along custom OSRM routes
                val syntheticEle = 85.0 + 18.0 * sin(i * 0.15)
                rawPoints.add(Triple(lat, lon, syntheticEle))
            }
            if (rawPoints.size < 2) return@withContext null
            buildRouteFromRawPoints(
                id = "osrm_${System.currentTimeMillis()}",
                name = String.format(Locale.US, "Custom Road (%.3f, %.3f)", startLat, startLon),
                region = "OSRM Online Map",
                rawPoints = rawPoints
            )
        }.getOrNull()
    }

    private fun buildAlpeDhuezRoute(): GpsRoute {
        val pts = listOf(
            Triple(45.0592, 6.0358, 724.0),
            Triple(45.0618, 6.0394, 785.0),
            Triple(45.0655, 6.0431, 860.0),
            Triple(45.0639, 6.0485, 945.0),
            Triple(45.0688, 6.0512, 1030.0),
            Triple(45.0721, 6.0472, 1118.0),
            Triple(45.0764, 6.0538, 1210.0),
            Triple(45.0795, 6.0589, 1315.0),
            Triple(45.0832, 6.0555, 1420.0),
            Triple(45.0869, 6.0621, 1535.0),
            Triple(45.0898, 6.0664, 1670.0),
            Triple(45.0924, 6.0706, 1815.0)
        )
        return buildRouteFromRawPoints(
            id = "alpe_dhuez",
            name = "Alpe d'Huez Climb",
            region = "French Alps, FR",
            rawPoints = pts
        )
    }

    private fun buildCentralParkRoute(): GpsRoute {
        val pts = listOf(
            Triple(40.7681, -73.9819, 28.0),
            Triple(40.7712, -73.9742, 34.0),
            Triple(40.7789, -73.9665, 29.0),
            Triple(40.7882, -73.9581, 36.0),
            Triple(40.7968, -73.9518, 22.0),
            Triple(40.7992, -73.9562, 48.0), // Harlem Hill climb
            Triple(40.7915, -73.9648, 38.0),
            Triple(40.7814, -73.9729, 32.0),
            Triple(40.7725, -73.9798, 27.0),
            Triple(40.7681, -73.9819, 28.0)
        )
        return buildRouteFromRawPoints(
            id = "central_park",
            name = "Central Park Full Loop",
            region = "New York, USA",
            rawPoints = pts
        )
    }

    private fun buildMarinaBayRoute(): GpsRoute {
        val pts = listOf(
            Triple(1.2816, 103.8636, 6.0),
            Triple(1.2848, 103.8592, 8.0),
            Triple(1.2895, 103.8544, 7.0),
            Triple(1.2932, 103.8615, 12.0),
            Triple(1.2965, 103.8742, 15.0),
            Triple(1.2884, 103.8712, 9.0),
            Triple(1.2816, 103.8636, 6.0)
        )
        return buildRouteFromRawPoints(
            id = "marina_bay",
            name = "Marina Bay Circuit",
            region = "Singapore, SG",
            rawPoints = pts
        )
    }

    private fun buildKintamaniBaliRoute(): GpsRoute {
        val pts = listOf(
            Triple(-8.2415, 115.3560, 1210.0),
            Triple(-8.2320, 115.3645, 1265.0),
            Triple(-8.2210, 115.3740, 1325.0),
            Triple(-8.2095, 115.3820, 1380.0),
            Triple(-8.1980, 115.3890, 1455.0),
            Triple(-8.2120, 115.3985, 1340.0),
            Triple(-8.2285, 115.3810, 1250.0),
            Triple(-8.2415, 115.3560, 1210.0)
        )
        return buildRouteFromRawPoints(
            id = "kintamani_bali",
            name = "Kintamani Highland Loop",
            region = "Bali, Indonesia",
            rawPoints = pts
        )
    }
}
