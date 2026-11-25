package app.fieldwatch.sweep

import app.fieldwatch.sweep.travel.TravelSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class HandsetBatteryTestTest {
    private val reading = BatteryReading(84, 10_000L, 3, false)
    private val balanced = BatteryCondition("trip")
    private val trial = BatteryTrial(reading, balanced)
    private val later = reading.copy(percent = 81, elapsedMs = reading.elapsedMs + 90 * 60_000L)

    @Test fun measuresPercentagePointsPerElapsedHourAndSurvivesReopening() {
        val restored = Json.decodeFromString<BatteryTestLog>(Json.encodeToString(BatteryTestLog(active = trial)))
        val result = restored.active!!.finish(later, balanced)
        assertEquals(2.0, result.pointsPerHour, 0.0)
        assertEquals(0.0, trial.finish(later.copy(percent = 84), balanced).pointsPerHour, 0.0)
        assertEquals("Travel · balanced · GPS off", result.label)
        assertFalse(Json.encodeToString(BatteryTestLog(results = listOf(result))).contains("trip"))
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
    }

    @Test fun legacyTravelSessionsUseBalancedAndLowPowerRoundTrips() {
        val legacy = Json.decodeFromString<TravelSession>("""{"id":"trip","startedAt":1000,"startedElapsedMs":500}""")
        assertFalse(legacy.lowPowerBluetooth)
        val lowPower = legacy.copy(lowPowerBluetooth = true)
        assertEquals(lowPower, Json.decodeFromString<TravelSession>(Json.encodeToString(lowPower)))
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
