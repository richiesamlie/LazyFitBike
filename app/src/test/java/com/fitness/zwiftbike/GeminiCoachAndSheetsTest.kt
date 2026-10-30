package com.fitness.zwiftbike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiCoachAndSheetsTest {

    @Test
    fun geminiCoachGeneratesContextAwarePacingCues() {
        // 1. Cadence low under load
        val lowCadenceTel = AthleteCoachTelemetry(
            currentWatts = 250,
            targetWatts = 250,
            currentRpm = 68,
            targetRpm = 90,
            currentBpm = 155,
            ftpWatts = 260,
            stepRemainingSec = 45,
            stepName = "Threshold Interval",
            slopeGradePercent = 2.0f,
            isDrafting = false
        )
        val cue1 = GeminiCoachEngine.generateLiveCoachingCue(lowCadenceTel)
        assertTrue(cue1.contains("Cadence low") && cue1.contains("68 RPM"))

        // 2. High Cardiac Strain
        val highHrTel = lowCadenceTel.copy(currentBpm = 188, currentRpm = 90)
        val cue2 = GeminiCoachEngine.generateLiveCoachingCue(highHrTel)
        assertTrue(cue2.contains("ceiling") && cue2.contains("188 BPM"))

        // 3. Drafting Bonus
        val draftingTel = lowCadenceTel.copy(isDrafting = true)
        val cue3 = GeminiCoachEngine.generateLiveCoachingCue(draftingTel)
        assertTrue(cue3.contains("Drafting bonus"))

        // 4. Overpowering recovery
        val overPowerTel = AthleteCoachTelemetry(
            currentWatts = 180,
            targetWatts = 120,
            currentRpm = 85,
            targetRpm = 85,
            currentBpm = 130,
            ftpWatts = 250,
            stepRemainingSec = 120,
            stepName = "Recovery",
            slopeGradePercent = 0f,
            isDrafting = false
        )
        val cue4 = GeminiCoachEngine.generateLiveCoachingCue(overPowerTel)
        assertTrue(cue4.contains("Easing up") && cue4.contains("+60W"))
    }

    @Test
    fun geminiCoachSynthesizesValidWorkoutFromNaturalLanguage() {
        val workout = GeminiCoachEngine.synthesizeWorkoutFromPrompt("45 min sweet spot climbing workout", 250)
        assertNotNull(workout)
        assertEquals(WorkoutType.CUSTOM, workout.category)
        assertTrue(workout.totalDurationSeconds in 2400..3000)
        assertTrue("Workout must have warmup", workout.steps.any { it.name == "Warmup" })
        assertTrue("Workout must have cooldown", workout.steps.any { it.name == "Cooldown" })
        assertTrue("Workout must have sweet spot steps", workout.steps.any { it.name.contains("Sweet Spot") })

        val vo2Workout = GeminiCoachEngine.synthesizeWorkoutFromPrompt("30 minute VO2 max microbursts", 250)
        assertTrue(vo2Workout.steps.any { it.zone == 5 })
    }

    @Test
    fun geminiCoachProducesComprehensivePostRideDebrief() {
        val summary = WorkoutSummary(
            workoutName = "Sweet Spot Test",
            category = WorkoutType.SWEET_SPOT,
            totalTimeSeconds = 3600,
            avgWatts = 210,
            maxWatts = 320,
            normalizedPowerWatts = 225,
            intensityFactor = 0.90f,
            tss = 81,
            totalStarsEarned = 15,
            maxStarsPossible = 15,
            overallCompliancePercent = 94.5f,
            timeInZones = mapOf(3 to 2400, 4 to 600, 1 to 600)
        )

        val debrief = GeminiCoachEngine.generatePostRideDebrief(summary, 70f)
        assertTrue(debrief.contains("Normalized Power 225W"))
        assertTrue(debrief.contains("TSS 81"))
        assertTrue(debrief.contains("Outstanding interval discipline"))
    }

    @Test
    fun googleSheetsSyncManagerBuildsCompliantCsvAndTsv() {
        val summary = WorkoutSummary(
            workoutName = "Threshold \"Surge\" Session",
            category = WorkoutType.THRESHOLD,
            totalTimeSeconds = 2700,
            avgWatts = 230,
            maxWatts = 380,
            normalizedPowerWatts = 248,
            intensityFactor = 0.95f,
            tss = 68,
            totalStarsEarned = 12,
            maxStarsPossible = 12,
            overallCompliancePercent = 92.0f,
            timeInZones = mapOf(4 to 1800, 1 to 900)
        )

        val csvLine = GoogleSheetsSyncManager.buildCsvRow(
            summary = summary,
            routeName = "Alpe d'Huez Climb",
            riderWeightKg = 70f,
            distanceKm = 18.5f,
            caloriesKcal = 620,
            avgRpm = 89,
            avgBpm = 164
        )

        // Check escaping of quotes and proper commas
        assertTrue(csvLine.contains("\"Alpe d'Huez Climb\""))
        assertTrue(csvLine.contains("248")) // NP
        assertTrue(csvLine.contains("68")) // TSS
        assertTrue(csvLine.contains("92.0")) // Compliance

        val tsvSnippet = GoogleSheetsSyncManager.buildTsvClipboardSnippet(
            summary = summary,
            routeName = "Alpe d'Huez Climb",
            riderWeightKg = 70f,
            distanceKm = 18.5f,
            caloriesKcal = 620,
            avgRpm = 89,
            avgBpm = 164
        )
        assertTrue(tsvSnippet.contains("\t"))
        assertTrue(!tsvSnippet.contains("\"\""))

        // Explicit test for route names containing commas (BUG-01 regression check)
        val commaRouteName = "Google Cycling Route (45.059, 6.036)"
        val csvWithComma = GoogleSheetsSyncManager.buildCsvRow(
            summary = summary,
            routeName = commaRouteName,
            riderWeightKg = 70f
        )
        assertTrue("CSV must quote comma-containing route names", csvWithComma.contains("\"$commaRouteName\""))

        val tsvWithComma = GoogleSheetsSyncManager.buildTsvClipboardSnippet(
            summary = summary,
            routeName = commaRouteName,
            riderWeightKg = 70f
        )
        val tsvColumns = tsvWithComma.split("\t")
        assertEquals("TSV must have exactly 17 columns even with commas in route name", 17, tsvColumns.size)
        assertEquals("TSV route name column must be preserved intact", commaRouteName, tsvColumns[2])
    }

    @Test
    fun googleMapsPolylineDecoderDecodesAccurately() {
        // Standard Google Encoded Polyline: (38.5, -120.2), (40.7, -120.95), (43.252, -126.453)
        val polyline = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        val points = GpsRouteEngine.decodePolyline(polyline)
        assertEquals(3, points.size)

        assertEquals(38.5, points[0].first, 0.001)
        assertEquals(-120.2, points[0].second, 0.001)

        assertEquals(40.7, points[1].first, 0.001)
        assertEquals(-120.95, points[1].second, 0.001)

        assertEquals(43.252, points[2].first, 0.001)
        assertEquals(-126.453, points[2].second, 0.001)

        assertEquals("gmp_git_agentskills_v1", GpsRouteEngine.GMP_SOLUTION_ID)
    }

    @Test
    fun googleCalendarSyncManagerFormatsWorkoutDetailsAccurately() {
        val workout = WorkoutEngine.getPresetById("preset_sweet_spot_2x20")
        val desc = GoogleCalendarSyncManager.formatWorkoutDescription(workout, ftpWatts = 220)

        assertTrue(desc.contains("LazyFitBike Structured Training Session"))
        assertTrue(desc.contains("Workout: Sweet Spot 2x20"))
        assertTrue(desc.contains("Rider Target FTP: 220 W"))
        assertTrue(desc.contains("Warmup"))
        assertTrue(desc.contains("Sweet Spot Block 1"))
        assertTrue(desc.contains("Zone 3"))
    }

    @Test
    fun firebaseRemoteConfigUpdatesPhysicsConstantsAndSerializesGhostRiders() {
        val remoteConfig = mapOf(
            "cda_aero" to "0.28",
            "crr_rolling" to "0.0035",
            "drafting_factor" to "0.70",
            "trainer_difficulty" to "0.50"
        )
        val updated = FirebaseConfig.applyRemoteConfig(remoteConfig)
        assertEquals(0.28f, updated.cdaAero, 0.001f)
        assertEquals(0.0035f, updated.crrRolling, 0.0001f)
        assertEquals(0.70f, updated.draftingAeroFactor, 0.001f)
        assertEquals(0.50f, updated.trainerDifficultyFactor, 0.001f)

        // Test Ghost Rider JSON serialization & deserialization for Firestore
        val ghost = GhostRiderRecord(
            riderId = "rider_abc_123",
            displayName = "Alex Ghost",
            wPerKg = 3.2f,
            speedMetersPerSec = 9.8f,
            distanceMeters = 1450f,
            routeId = "alpe_dhuez"
        )
        val jsonStr = FirebaseConfig.serializeGhostRiders(listOf(ghost))
        assertTrue(jsonStr.contains("rider_abc_123"))
        assertTrue(jsonStr.contains("Alex Ghost"))

        val restoredList = FirebaseConfig.deserializeGhostRiders(jsonStr)
        assertEquals(1, restoredList.size)
        assertEquals("Alex Ghost", restoredList.first().displayName)
        assertEquals(3.2f, restoredList.first().wPerKg, 0.01f)
        assertEquals("alpe_dhuez", restoredList.first().routeId)
    }

    @Test
    fun telemetryBroadcasterSerializesAndRestoresPacketsCleanly() {
        val packet = SpectatorPacket(
            riderName = "Richie Sam",
            timestampMs = 1730278800000L,
            powerWatts = 285,
            heartRateBpm = 162,
            cadenceRpm = 92,
            speedKmh = 38.4f,
            distanceKm = 14.250f,
            slopeGradePercent = 1.5f,
            normalizedPowerWatts = 278,
            powerZone = 4,
            hrZone = 4,
            caloriesKcal = 420,
            routeName = "Watopia Hilly",
            lat = -11.645210,
            lon = 166.953120,
            elevationMeters = 185.4,
            workoutActive = true,
            workoutName = "VO2 Max Attack",
            workoutStepName = "Interval 3",
            workoutTargetWatts = 310,
            workoutRemainingSec = 45
        )

        val json = TelemetryBroadcaster.toJson(packet)
        assertTrue(json.contains("\"riderName\":\"Richie Sam\""))
        assertTrue(json.contains("\"powerWatts\":285"))
        assertTrue(json.contains("\"speedKmh\":38.40"))
        assertTrue(json.contains("\"workoutName\":\"VO2 Max Attack\""))

        val parsed = TelemetryBroadcaster.fromJson(json)
        assertNotNull(parsed)
        assertEquals("Richie Sam", parsed?.riderName)
        assertEquals(285, parsed?.powerWatts)
        assertEquals(162, parsed?.heartRateBpm)
        assertEquals(92, parsed?.cadenceRpm)
        assertEquals(38.4f, parsed?.speedKmh ?: 0f, 0.01f)
        assertEquals(14.25f, parsed?.distanceKm ?: 0f, 0.01f)
        assertEquals("VO2 Max Attack", parsed?.workoutName)
        assertEquals("Interval 3", parsed?.workoutStepName)
        assertEquals(310, parsed?.workoutTargetWatts)
        assertEquals(45, parsed?.workoutRemainingSec)

        // Invalid endpoint returns false safely without crashing
        val success = TelemetryBroadcaster.broadcast("", packet)
        assertEquals(false, success)
    }

    @Test
    fun googleDriveSyncManagerGeneratesSanitizedCloudFilenames() {
        val fileName = GoogleDriveSyncManager.buildBackupFileName(
            prefix = "GPX",
            routeOrWorkoutName = "Alpe du Zwift / Stage 1!",
            extension = "gpx"
        )
        assertTrue(fileName.startsWith("LazyFitBike_GPX_Alpe_du_Zwift___Stage_1_"))
        assertTrue(fileName.endsWith(".gpx"))
        // Check no illegal characters
        assertTrue(!fileName.contains("/") && !fileName.contains("!"))
    }
}
