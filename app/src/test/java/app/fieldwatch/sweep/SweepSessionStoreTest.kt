package app.fieldwatch.sweep

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SweepSessionStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = true }

    @Test
    fun roundTripRetainsMeasuredEvidenceAndCompleteState() {
        val directory = temporary.newFolder()
        val session = session("roundtrip").copy(
            state = SweepSessionState.COMPLETE,
            endedAt = 2_000,
            findings = listOf(finding("drone").copy(
                category = SweepCategory.DRONE,
                signatureNames = listOf("FAA Remote ID"),
                evidence = listOf(SweepEvidence("Basic ID", "TEST0001")),
                nextSteps = listOf("Check the stated coordinates"),
                dronePins = listOf(SweepPin(SweepPinKind.TAKEOFF, 39.9, -73.9, 1_200)),
                droneId = "TEST0001",
            )),
            coverage = SweepCoverage(wifiSupported = true, wifiSamples = 4, droppedSamples = 2),
            startBattery = 94,
            endBattery = 92,
            phone = "Test phone",
            androidVersion = "15",
        )
        SweepSessionStore(directory).save(session)
        assertEquals(listOf(session), SweepSessionStore(directory).load())
    }

    @Test
    fun activeCheckpointSurvivesRestartForCoordinatorRecovery() {
        val directory = temporary.newFolder()
        val active = session("active").copy(findings = listOf(finding("one")))
        SweepSessionStore(directory).save(active)
        assertEquals(listOf(active), SweepSessionStore(directory).load())
    }

    @Test
    fun unfinishedReplacementAfterProcessDeathCannotOverrideCommittedCheckpoint() {
        val directory = temporary.newFolder()
        val active = session("active")
        SweepSessionStore(directory).save(active)
        val abandoned = File(directory, ".sessions-abandoned.tmp")
        abandoned.writeText(json.encodeToString(listOf(session("never-committed"))))
        assertEquals(listOf(active), SweepSessionStore(directory).load())
        assertFalse(abandoned.exists())
    }

    @Test
    fun saveUpdatesSessionWithoutDuplicatingIt() {
        val directory = temporary.newFolder()
        val store = SweepSessionStore(directory)
        store.save(session("same"))
        val complete = session("same").copy(state = SweepSessionState.COMPLETE, endedAt = 2_000)
        store.save(complete)
        assertEquals(listOf(complete), SweepSessionStore(directory).load())
    }

    @Test
    fun malformedRowPreservesGoodRowsAndArchivesOriginal() {
        val directory = temporary.newFolder()
        val good = session("good")
        val contents = "[${json.encodeToString(good)}, {\"id\":\"broken\", \"startedAt\":\"not a number\"}]"
        File(directory, "sessions.json").writeText(contents)
        assertEquals(listOf(good), SweepSessionStore(directory).load())
        val backup = directory.listFiles()!!.single { it.name.startsWith("sessions.corrupt-") }
        assertEquals(contents, backup.readText())
        assertEquals(listOf(good), SweepSessionStore(directory).load())
        assertEquals(1, directory.listFiles()!!.count { it.name.startsWith("sessions.corrupt-") })
    }

    @Test
    fun malformedEntireFileReturnsEmptyAndCanSaveAfterRecovery() {
        val directory = temporary.newFolder()
        val original = "[{ broken"
        File(directory, "sessions.json").writeText(original)
        val store = SweepSessionStore(directory)
        assertTrue(store.load().isEmpty())
        assertEquals(original, directory.listFiles()!!.single { it.name.startsWith("sessions.corrupt-") }.readText())
        store.save(session("next"))
        assertEquals(listOf(session("next")), SweepSessionStore(directory).load())
    }

    @Test
    fun malformedRootDoesNotMasqueradeAsAValidEmptyDatabase() {
        val directory = temporary.newFolder()
        File(directory, "sessions.json").writeText("{\"unexpected\": true}")
        assertTrue(SweepSessionStore(directory).load().isEmpty())
        assertEquals(1, directory.listFiles()!!.count { it.name.startsWith("sessions.corrupt-") })
    }

    @Test
    fun unknownFutureFieldsDoNotDiscardExistingSessions() {
        val directory = temporary.newFolder()
        val contents = json.encodeToString(session("future")).dropLast(1) + ",\"futureFeature\":true}"
        File(directory, "sessions.json").writeText("[$contents]")
        assertEquals(listOf(session("future")), SweepSessionStore(directory).load())
        assertFalse(directory.listFiles()!!.any { it.name.startsWith("sessions.corrupt-") })
    }

    @Test
    fun failedAtomicReplacementPreservesPriorDatabaseAndCleansTemporaryFile() {
        val directory = temporary.newFolder()
        SweepSessionStore(directory).save(session("before"))
        val oldBytes = File(directory, "sessions.json").readBytes()
        val broken = SweepSessionStore(directory) { temporary, destination ->
            assertTrue(temporary.isFile)
            assertEquals(directory, temporary.parentFile)
            assertTrue(destination.readBytes().contentEquals(oldBytes))
            throw IOException("Simulated atomic replacement failure")
        }
        try {
            broken.save(session("after", 2_000))
            fail("Save must report the failure")
        } catch (_: IOException) {
            // The durable state must still be the previous successful save.
        }
        assertTrue(File(directory, "sessions.json").readBytes().contentEquals(oldBytes))
        assertEquals(listOf(session("before")), SweepSessionStore(directory).load())
        assertFalse(directory.listFiles()!!.any { it.name.startsWith(".sessions-") })
    }

    @Test
    fun demoNeverCreatesDatabaseOrOverwritesRealSession() {
        val directory = File(temporary.newFolder(), "not-created")
        val store = SweepSessionStore(directory)
        store.save(session("demo").copy(demo = true))
        assertFalse(directory.exists())
        val real = session("real")
        store.save(real)
        store.save(real.copy(demo = true, findings = listOf(finding("fake"))))
        assertEquals(listOf(real), SweepSessionStore(directory).load())
    }

    @Test
    fun legacyDemoRowsAreRemovedFromDisk() {
        val directory = temporary.newFolder()
        File(directory, "sessions.json").writeText(json.encodeToString(listOf(
            session("real"), session("demo").copy(demo = true),
        )))
        assertEquals(listOf(session("real")), SweepSessionStore(directory).load())
        assertFalse(File(directory, "sessions.json").readText().contains("\"demo\":true"))
    }

    @Test
    fun deletionRemovesOnlyRequestedSessionAndClearRemovesRecoveryCopies() {
        val directory = temporary.newFolder()
        val store = SweepSessionStore(directory)
        store.save(session("first"))
        store.save(session("second", 2_000))
        File(directory, "sessions.corrupt-deleted.json").writeText("private data for first")
        store.delete("first")
        assertFalse(File(directory, "sessions.corrupt-deleted.json").exists())
        store.delete("missing")
        assertEquals(listOf(session("second", 2_000)), SweepSessionStore(directory).load())
        File(directory, "sessions.corrupt-test.json").writeText("private old data")
        File(directory, ".sessions-old.tmp").writeText("unfinished data")
        File(directory, "unrelated.txt").writeText("keep")
        store.deleteAll()
        assertTrue(SweepSessionStore(directory).load().isEmpty())
        assertEquals(setOf("sessions.json", "unrelated.txt"), directory.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun historyKeepsNewest100SessionsAndLatest1000Findings() {
        val directory = temporary.newFolder()
        val store = SweepSessionStore(directory)
        for (index in 0..104) store.save(session("session-$index", index.toLong()))
        val sessions = store.load()
        assertEquals(100, sessions.size)
        assertEquals("session-104", sessions.first().id)
        assertEquals("session-5", sessions.last().id)
        val big = session("big", 1_000).copy(findings = (0..1_004).map { finding("f-$it", it.toLong()) })
        store.save(big)
        val stored = SweepSessionStore(directory).load().first()
        assertEquals(1_000, stored.findings.size)
        assertEquals("f-1004", stored.findings.first().id)
        assertEquals("f-5", stored.findings.last().id)
    }

    @Test
    fun externallyOversizedRecordsAreBoundedOnLoadToo() {
        val directory = temporary.newFolder()
        val sessions = (0..104).map { index -> session("session-$index", index.toLong()).copy(
            findings = if (index == 104) (0..1_004).map { finding("f-$it", it.toLong()) } else emptyList(),
        ) }
        File(directory, "sessions.json").writeText(json.encodeToString(sessions))
        val loaded = SweepSessionStore(directory).load()
        assertEquals(100, loaded.size)
        assertEquals(1_000, loaded.first().findings.size)
        assertEquals(100, Json.decodeFromString<List<SweepSession>>(File(directory, "sessions.json").readText()).size)
    }

    @Test
    fun sessionIdsCannotBecomePaths() {
        val directory = temporary.newFolder()
        val store = SweepSessionStore(directory)
        for (id in listOf("../escape", "/absolute", "", ".", "..", "a\\b", "x".repeat(129))) {
            try {
                store.save(session(id))
                fail("Unsafe ID should be rejected: $id")
            } catch (_: IllegalArgumentException) { }
        }
        assertFalse(File(directory.parentFile, "escape").exists())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun concurrentStoreInstancesDoNotLoseEachOthersUpdates() {
        val directory = temporary.newFolder()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0..39).map { index -> Callable {
                SweepSessionStore(directory).save(session("parallel-$index", index.toLong()))
            } }
            executor.invokeAll(jobs).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(40, SweepSessionStore(directory).load().size)
    }

    @Test
    fun nonRegularDatabaseReportsIoFailureWithoutReplacingIt() {
        val directory = temporary.newFolder()
        Files.createDirectory(File(directory, "sessions.json").toPath())
        try {
            SweepSessionStore(directory).save(session("new"))
            fail("Unusable storage must report a failure")
        } catch (_: IOException) { }
        assertTrue(File(directory, "sessions.json").isDirectory)
    }

    private fun session(id: String, startedAt: Long = 1_000) = SweepSession(id = id, startedAt = startedAt)

    private fun finding(id: String, lastSeenAt: Long = 1_200) = SweepFinding(
        id = id,
        name = "Observed device",
        category = SweepCategory.OTHER,
        transport = "BLE",
        rssi = -60,
        firstSeenAt = 1_000,
        lastSeenAt = lastSeenAt,
        observations = 2,
    )
}
