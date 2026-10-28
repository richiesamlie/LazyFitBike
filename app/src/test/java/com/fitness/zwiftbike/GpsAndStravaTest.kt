package com.fitness.zwiftbike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsAndStravaTest {

    @Test
    fun presetRoutesInterpolateCoordinatesAndElevationAccurately() {
        val alpe = GpsRouteEngine.getPresetById("alpe_dhuez")
        assertTrue("Alpe d'Huez should be > 5km", alpe.totalDistanceMeters > 5000f)

        val startPt = GpsRouteEngine.interpolateAtDistance(alpe, 0f)
        val midPt = GpsRouteEngine.interpolateAtDistance(alpe, 2500f)
        assertTrue("Midpoint elevation (${midPt.ele}) should exceed start (${startPt.ele})", midPt.ele > startPt.ele)
    }

    @Test
    fun parseGpxRouteExtractsTrackpointsAndBuildsRoute() {
        val sampleGpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk>
                <name>Test GPX Climb</name>
                <trkseg>
                  <trkpt lat="45.0600" lon="6.0400"><ele>750.0</ele></trkpt>
                  <trkpt lat="45.0650" lon="6.0450"><ele>810.0</ele></trkpt>
                  <trkpt lat="45.0700" lon="6.0500"><ele>880.0</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val parsed = GpsRouteEngine.parseGpxRoute(sampleGpx)
        assertNotNull(parsed)
        assertEquals("Test GPX Climb", parsed?.name)
        assertTrue((parsed?.totalDistanceMeters ?: 0f) > 500f)
        assertTrue((parsed?.totalElevationGainMeters ?: 0f) > 100f)
    }

    @Test
    fun buildStravaGpxXmlIncludesGarminHrCadenceAndPowerExtensions() {
        val pts = listOf(
            RecordedTrackPoint(
                lat = 45.059200,
                lon = 6.035800,
                eleMeters = 724.0,
                timestampEpochMs = 1730100000000L,
                watts = 218,
                rpm = 89,
                bpm = 151
            ),
            RecordedTrackPoint(
                lat = 45.059450,
                lon = 6.036100,
                eleMeters = 726.2,
                timestampEpochMs = 1730100001000L,
                watts = 224,
                rpm = 91,
                bpm = 153
            )
        )

        val xml = StravaSyncManager.buildStravaGpxXml("Alpe d'Huez Climb", pts)
        assertTrue(xml.contains("<trkpt lat=\"45.059200\" lon=\"6.035800\">"))
        assertTrue(xml.contains("<ele>724.0</ele>"))
        assertTrue(xml.contains("<power>218</power>"))
        assertTrue(xml.contains("<gpxtpx:hr>151</gpxtpx:hr>"))
        assertTrue(xml.contains("<gpxtpx:cad>89</gpxtpx:cad>"))
    }
}
