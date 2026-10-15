package app.fieldwatch.sweep.travel

import app.fieldwatch.sweep.SweepRadioGate
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.io.IOException

class TravelStoreAndLeaseTest {
    private val now = 1_000_000_000L
    private val address = "AA:BB:CC:DD:EE:01"
    private fun archive() = TravelArchive(listOf(TravelSession("trip", now, 1_000,
        locationConsent = true, trackers = listOf(TravelTracker(address, "Item-finder signal", true,
            listOf(TravelSample(now, 1_000, -60, TravelLocation(40.1, -74.1, 5f, 1_000))))))), setOf("11:22:33:44:55:66"))

    @Test fun interruptedActiveArchiveLoadsAsHistoryNeverAsResumableActiveMode() {
        val directory = Files.createTempDirectory("travel-recovery").toFile()
        try {
            val store = TravelStore(directory)
            store.save(archive(), now)
            val recovered = store.load(now)
            assertEquals(TravelSessionState.INTERRUPTED, recovered.sessions.single().state)
            assertTrue(recovered.sessions.single().error!!.contains("Start it explicitly"))
            assertEquals(archive().ignoredAddresses, recovered.ignoredAddresses)
            assertFalse(TravelServiceLease(SweepRadioGate()).held())
        } finally { directory.deleteRecursively() }
    }

    @Test fun backwardsClockCorrectionDoesNotDeleteTheInterruptedEvidence() {
        val directory = Files.createTempDirectory("travel-backwards-clock").toFile()
        try {
            val beforeCorrection = archive().sessions.single()
            val interrupted = beforeCorrection.copy(state = TravelSessionState.INTERRUPTED, endedAt = null,
                error = app.fieldwatch.sweep.SessionTimeWindow.CLOCK_CHANGED_MESSAGE)
            val correctedNow = now - 3_600_000L
            val store = TravelStore(directory)
            assertTrue(store.save(TravelArchive(listOf(interrupted)), correctedNow))
            val recovered = store.load(correctedNow).sessions.single()
            assertEquals(interrupted, recovered)
            assertNull(recovered.endedAt)
            assertEquals(beforeCorrection.trackers, recovered.trackers)
            // Retention resumes normally once wall time advances beyond the saved session.
            assertTrue(store.load(now + TravelLimits.RETENTION_MS + 1).sessions.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun deletionRemovesSamplesCoordinatesAndIncompleteRecoveryFile() {
        val directory = Files.createTempDirectory("travel-delete").toFile()
        val store = TravelStore(directory)
        store.save(archive(), now)
        File(directory, "observations.pending").writeText("40.1,-74.1,$address")
        store.markLocationErasurePending()
        store.deleteAll()
        assertFalse(directory.exists())
        assertEquals(TravelArchive(), store.load(now))
    }

    @Test fun failedErasureStaysPendingAndRecoveryRedactsUntilDurableRetrySucceeds() {
        val directory = Files.createTempDirectory("travel-erasure-retry").toFile()
        try {
            TravelStore(directory).save(archive(), now)
            var failReplacement = true
            val store = TravelStore(directory) { pending, destination ->
                if (failReplacement) false else pending.renameTo(destination)
            }
            store.markLocationErasurePending()
            val redacted = archive().copy(sessions = archive().sessions.map { TravelPrivacy.withoutLocations(it) })
            try { store.save(redacted, now); fail("Expected the injected replacement failure") } catch (_: IOException) { }
            assertTrue(store.locationErasurePending())
            assertTrue(File(directory, "observations.json").readText().contains("40.1"))
            assertFalse(File(directory, "observations.pending").exists())
            val recovered = TravelStore(directory).load(now)
            assertTrue(TravelPrivacy.coordinateFree(recovered))
            assertEquals(TravelSessionState.INTERRUPTED, recovered.sessions.single().state)
            // Loading does not claim that the original coordinate-bearing file was erased.
            assertTrue(store.locationErasurePending())
            failReplacement = false
            assertTrue(store.save(recovered, now))
            store.completeLocationErasure()
            assertFalse(store.locationErasurePending())
            assertFalse(File(directory, "observations.json").readText().contains("40.1"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidatedSnapshotCannotReplaceNewerPrivacyState() {
        val directory = Files.createTempDirectory("travel-obsolete-write").toFile()
        try {
            val store = TravelStore(directory)
            val clean = archive().copy(sessions = archive().sessions.map { TravelPrivacy.withoutLocations(it) })
            store.save(clean, now)
            assertFalse(store.save(archive(), now) { false })
            assertTrue(TravelPrivacy.coordinateFree(store.load(now)))
            assertFalse(File(directory, "observations.pending").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun unreadableHistoryCannotBeReplacedUntilExplicitReset() {
        val directory = Files.createTempDirectory("travel-unreadable-history").toFile()
        try {
            val store = TravelStore(directory)
            val file = File(directory, "observations.json")
            val damaged = "{unfinished original with private coordinates 40.1,-74.1"
            file.writeText(damaged)
            assertTrue(runCatching { store.load(now) }.isFailure)
            assertTrue(runCatching { store.save(archive(), now) }.isFailure)
            assertEquals(damaged, file.readText())
            assertFalse(File(directory, "observations.pending").exists())

            store.deleteAll()
            assertTrue(store.save(archive(), now))
            assertEquals("trip", store.load(now).sessions.single().id)
        } finally { directory.deleteRecursively() }
    }

    @Test fun damageAfterLoadCannotTurnAnErasureRetryIntoImplicitHistoryReset() {
        val directory = Files.createTempDirectory("travel-damaged-erasure").toFile()
        try {
            val store = TravelStore(directory)
            store.save(archive(), now)
            val previouslyLoaded = store.load(now)
            val file = File(directory, "observations.json")
            val damaged = "{damaged after load; original evidence still retained"
            file.writeText(damaged)
            store.markLocationErasurePending()
            val redacted = previouslyLoaded.copy(sessions = previouslyLoaded.sessions.map { TravelPrivacy.withoutLocations(it) })
            assertTrue(runCatching { store.save(redacted, now) }.isFailure)
            assertEquals(damaged, file.readText())
            assertTrue(store.locationErasurePending())
            store.deleteAll()
            assertFalse(store.locationErasurePending())
            assertEquals(TravelArchive(), store.load(now))
        } finally { directory.deleteRecursively() }
    }

    @Test fun retentionAndCapacityBoundsAndNoConsentCoordinatesAreEnforced() {
        val baseline = archive().sessions.single()
        val tooManySamples = (0..100).map { baseline.trackers.single().samples.single().copy(elapsedMs = 1_000L + it * 120_000) }
        val tooManyTrackers = (0..100).map { baseline.trackers.single().copy(address = "tracker-$it", samples = tooManySamples) }
        val sessions = (0..20).map { baseline.copy(id = "$it", startedAt = now - it, locationConsent = false, trackers = tooManyTrackers) } +
            baseline.copy(id = "expired", startedAt = now - TravelLimits.RETENTION_MS - 1)
        val bounded = TravelStore.bounded(TravelArchive(sessions), now)
        assertEquals(TravelLimits.MAX_SESSIONS, bounded.sessions.size)
        assertTrue(bounded.sessions.none { it.id == "expired" })
        assertEquals(TravelLimits.MAX_TRACKERS, bounded.sessions.first().trackers.size)
        assertEquals(TravelLimits.MAX_SAMPLES_PER_TRACKER, bounded.sessions.first().trackers.first().samples.size)
        assertTrue(bounded.sessions.flatMap { it.trackers }.flatMap { it.samples }.all { it.location == null })
    }

    @Test fun gateIsHeldUntilMatchingServiceTeardown() {
        val gate = SweepRadioGate()
        val lease = TravelServiceLease(gate)
        assertTrue(lease.reserve("one"))
        assertFalse(gate.acquire("rf:scan"))
        assertTrue(lease.attach("one", "service-one"))
        assertFalse(lease.releaseUncreated("one"))
        assertFalse(lease.release("one", "other-service"))
        assertFalse(lease.release("old", "service-one"))
        assertTrue(lease.held())
        assertTrue(gate.held())
        assertTrue(lease.release("one", "service-one"))
        assertFalse(gate.held())
    }

    @Test fun uncreatedCancellationCannotReleaseNewOrOtherModeToken() {
        val gate = SweepRadioGate()
        val lease = TravelServiceLease(gate)
        assertTrue(lease.reserve("old"))
        assertTrue(lease.releaseUncreated("old"))
        assertTrue(lease.reserve("new"))
        assertFalse(lease.releaseUncreated("old"))
        assertFalse(lease.attach("old", "late-service"))
        assertTrue(gate.held())
        assertTrue(lease.releaseUncreated("new"))
        assertTrue(gate.acquire("radar:one"))
        assertFalse(lease.reserve("travel"))
        assertFalse(lease.releaseUncreated("travel"))
        assertTrue(gate.held())
    }
}
