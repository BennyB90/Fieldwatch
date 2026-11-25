package app.fieldwatch.sweep

import app.fieldwatch.sweep.travel.TravelLocation
import app.fieldwatch.sweep.travel.TravelSample
import app.fieldwatch.sweep.travel.TravelSession
import app.fieldwatch.sweep.travel.TravelTracker
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class HandsetBatteryTestTest {
    private val reading = BatteryReading(84, 10_000L, 3, false)
    private val balanced = BatteryCondition("trip")
    private val trial = BatteryTrial(reading, balanced)
    private val later = reading.copy(percent = 81, elapsedMs = reading.elapsedMs + 90 * 60_000L)

    @Test fun measuresPercentagePointsPerElapsedHourAndSurvivesReopening() {
        val restored = Json.decodeFromString<BatteryTestLog>(Json.encodeToString(BatteryTestLog(active = trial)))
        val result = restored.active!!.finish(later, balanced, completedAt = 1_760_000_000_000L)
        assertEquals(2.0, result.pointsPerHour, 0.0)
        assertEquals(0.0, trial.finish(later.copy(percent = 84), balanced).pointsPerHour, 0.0)
        assertEquals(0.0, BatteryResult("Zero", 80, 79, 0L).pointsPerHour, 0.0)
        assertEquals("Travel · balanced · GPS off", result.label)
        assertFalse(Json.encodeToString(BatteryTestLog(results = listOf(result))).contains("trip"))
    }

    @Test fun recordsSightingAndGpsCountsWithoutRetainingTrackerAddressesOrCoordinates() {
        val gpsCondition = BatteryCondition(travelId = "trip", lowPower = false, gps = true)
        val gpsTrial = BatteryTrial(reading, gpsCondition)
        val travel = TravelSession(
            id = "trip",
            startedAt = 1_000L,
            startedElapsedMs = 500L,
            locationConsent = true,
            trackers = listOf(
                TravelTracker(
                    address = "AA:BB:CC:DD:EE:FF",
                    label = "AirTag",
                    randomizedAddress = true,
                    samples = listOf(
                        TravelSample(2_000L, 1_500L, -62, TravelLocation(37.7749, -122.4194, 8f, 1_500L)),
                        TravelSample(602_000L, 601_500L, -65, null),
                    ),
                ),
            ),
        )
        val result = gpsTrial.finish(later, gpsCondition, completedAt = 1_760_000_000_000L, travelSession = travel)
        assertEquals(1, result.trackerCount)
        assertEquals(2, result.sightingCount)
        assertEquals(1, result.gpsSightingCount)
        val encoded = Json.encodeToString(result)
        assertFalse(encoded.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(encoded.contains("37.7749"))
        assertFalse(encoded.contains("-122.4194"))

        val redactedReport = BatteryTrialReports.text(
            results = listOf(result),
            includeDevice = false,
            deviceModel = "Google Pixel 9",
            androidRelease = "16 (API 36, BP2A.250605.031)",
            sdkInt = 36,
        )
        assertTrue(redactedReport.contains("Device: Withheld"))
        assertTrue(redactedReport.contains("Android: Withheld"))
        assertFalse(redactedReport.contains("Pixel 9"))
        assertTrue(redactedReport.contains("2.00 points/hour"))
        assertTrue(redactedReport.contains("2 spaced observations across 1 tracker signal(s) (1 of 2 with usable GPS fix)"))

        val deviceReport = BatteryTrialReports.text(
            results = listOf(result),
            includeDevice = true,
            deviceModel = "Google Pixel 9",
            androidRelease = "16 (API 36, BP2A.250605.031)",
            sdkInt = 36,
        )
        assertTrue(deviceReport.contains("Device: Google Pixel 9"))
        assertTrue(deviceReport.contains("Android: 16 · API 36"))
        assertFalse(deviceReport.contains("BP2A"))
    }

    @Test fun rejectsChargingRebootReversedTimeAndChangedConditions() {
        for (invalid in listOf(later.copy(plugged = true), later.copy(boot = 4),
            later.copy(elapsedMs = reading.elapsedMs), later.copy(percent = 85))) {
            assertThrows(IllegalArgumentException::class.java) { trial.finish(invalid, balanced) }
        }
        for (changed in listOf(BatteryCondition(), balanced.copy(travelId = "next"),
            balanced.copy(lowPower = true), balanced.copy(gps = true))) {
            assertThrows(IllegalArgumentException::class.java) { trial.finish(later, changed) }
        }
        assertThrows(IllegalArgumentException::class.java) { BatteryTrialReports.text(emptyList()) }
    }

    @Test fun legacyTravelSessionsAndBatteryLogsRoundTrip() {
        val legacy = Json.decodeFromString<TravelSession>("""{"id":"trip","startedAt":1000,"startedElapsedMs":500}""")
        assertFalse(legacy.lowPowerBluetooth)
        val lowPower = legacy.copy(lowPowerBluetooth = true)
        assertEquals(lowPower, Json.decodeFromString<TravelSession>(Json.encodeToString(lowPower)))
        val legacyLog = Json.decodeFromString<BatteryTestLog>(
            """{"results":[{"label":"Idle baseline","startPercent":90,"endPercent":89,"durationMs":3600000}]}""",
        )
        assertEquals(1.0, legacyLog.results.single().pointsPerHour, 0.0)
        assertNull(legacyLog.results.single().sightingCount)
    }

    @Test fun skipsOnlyCommittedSnapshotsAndRetriesAfterAnErrorOrGenerationChange() {
        val writer = CheckpointWriter<Pair<Long, String>>()
        val first = 0L to "snapshot"
        var attempts = 0
        fun write(value: Pair<Long, String>, accepted: Boolean = true) =
            writer.writeIfChanged(value) { attempts++; accepted }
        assertFalse(write(first, false))
        assertTrue(write(first))
        assertTrue(write(first))
        assertEquals(2, attempts)
        assertTrue(write(1L to "snapshot"))
        assertEquals(3, attempts)
        assertThrows(java.io.IOException::class.java) {
            writer.writeIfChanged(1L to "new") { throw java.io.IOException() }
        }
        assertTrue(write(1L to "new"))
        writer.clear()
        assertTrue(write(1L to "new"))
        assertEquals(5, attempts)
    }
}
