package app.fieldwatch.sweep.network

import java.io.File
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NetworkStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_700_000_000_000L

    @Test
    fun activeCheckpointAndEndpointEvidenceSurviveRestartBeforeExplicitRecovery() {
        val directory = temporary.newFolder()
        val checkpoint = session().copy(coverage = NetworkCoverage(true, listOf("_http._tcp."), 1, 0, true,
            typesStarted = listOf("_http._tcp."), status = "Listening"))
        NetworkSessionStore(directory).save(checkpoint, now)
        assertEquals(listOf(checkpoint), NetworkSessionStore(directory).load(now))
        val recovered = NetworkSessionStore(directory).recoverInterrupted(now).single()
        assertEquals(NetworkSessionState.INTERRUPTED, recovered.state)
        assertNull(recovered.endedAt)
        assertEquals(checkpoint.endpoints, recovered.endpoints)
        assertEquals(listOf(recovered), NetworkSessionStore(directory).load(now))
    }

    @Test
    fun completedAndRouterReportedStateRemainSeparateDuringProcessRecovery() {
        val directory = temporary.newFolder()
        val done = session("complete").copy(state = NetworkSessionState.COMPLETE, endedAt = now)
        val inventory = NetworkSession("router", now, now, NetworkSessionState.COMPLETE,
            listOf(NetworkEndpoint("one", "", null, null, NetworkSource.ROUTER_IMPORT,
                addresses = listOf("192.168.1.44"), reportedCurrent = false,
                reportedLastSeenAt = now - 120_000, reportedMacAddress = "AA:BB:CC:DD:EE:FF", importedAt = now)),
            source = NetworkSource.ROUTER_IMPORT, networkLabel = "Owner label", capturedAt = now - 60_000)
        NetworkSessionStore(directory).replace(listOf(done, inventory), now)
        val loaded = NetworkSessionStore(directory).recoverInterrupted(now)
        assertEquals(setOf(done, inventory), loaded.toSet())
        assertTrue(loaded.all { it.state == NetworkSessionState.COMPLETE })
    }

    @Test
    fun authoritativeReplaceDoesNotMergeDeletedHistoryBackIntoANewActiveCheckpoint() {
        val directory = temporary.newFolder()
        val store = NetworkSessionStore(directory)
        val previous = session("previous").copy(state = NetworkSessionState.COMPLETE, endedAt = now)
        val active = session("active")
        store.replace(listOf(previous, active), now)
        store.replace(listOf(active), now)
        store.replace(listOf(active.copy(coverage = NetworkCoverage(status = "Updated"))), now)
        val loaded = NetworkSessionStore(directory).load(now)
        assertEquals(listOf("active"), loaded.map { it.id })
        assertEquals("Updated", loaded.single().coverage.status)
    }

    @Test
    fun historyRetainsNewest20SessionsWithinSevenDaysAndExpiryRemovesOldFileRecords() {
        val directory = temporary.newFolder()
        val store = NetworkSessionStore(directory)
        val recent = (0..24).map { index -> session("s-$index", now - index * 1_000) }
        val expired = session("expired", now - NetworkLimits.RETENTION_MS - 1)
        store.replace(recent + expired, now)
        assertEquals(20, store.load(now).size)
        assertEquals(recent.take(20).map { it.id }, store.load(now).map { it.id })
        assertFalse(File(directory, "network-sessions.json").readText().contains("expired"))
        assertTrue(store.load(now + NetworkLimits.RETENTION_MS + 1).isEmpty())
        assertFalse(File(directory, "network-sessions.json").readText().contains("s-0"))
    }

    @Test
    fun retentionBoundaryIsInclusiveAndUsesImportTimeRatherThanOldReportedCapture() {
        val directory = temporary.newFolder()
        val boundary = NetworkSession("boundary", now - NetworkLimits.RETENTION_MS)
        val inventory = NetworkSession("recent-import", now, now, NetworkSessionState.COMPLETE,
            source = NetworkSource.ROUTER_IMPORT, capturedAt = now - 30L * 86_400_000)
        val store = NetworkSessionStore(directory)
        store.replace(listOf(boundary, inventory), now)
        assertEquals(listOf(inventory, boundary), store.load(now))
        assertEquals(listOf(inventory), store.load(now + 1))
    }

    @Test
    fun recordAndIdentityBoundsRejectAnInvalidWriteWithoutDiscardingOldHistory() {
        val directory = temporary.newFolder()
        val store = NetworkSessionStore(directory)
        val good = session()
        store.save(good, now)
        invalid { store.replace(listOf(good, good), now) }
        invalid { store.save(good.copy(id = "../outside"), now) }
        invalid { store.save(good.copy(endpoints = List(129) { index -> good.endpoints.single().copy(id = "r-$index") }), now) }
        invalid { store.save(good.copy(endpoints = listOf(good.endpoints.single(), good.endpoints.single())), now) }
        invalid { store.save(good.copy(endpoints = listOf(good.endpoints.single().copy(port = 65_536))), now) }
        invalid { store.save(good.copy(endpoints = listOf(good.endpoints.single().copy(txt = mapOf("key" to "x".repeat(4_097))))), now) }
        assertEquals(listOf(good), store.load(now))
    }

    @Test
    fun sourceAndTimestampValidationPreventsReportedStatusBecomingLocalObservation() {
        val directory = temporary.newFolder()
        val store = NetworkSessionStore(directory)
        val nsd = session()
        invalid { store.save(nsd.copy(endpoints = listOf(nsd.endpoints.single().copy(reportedCurrent = true))), now) }
        invalid { store.save(nsd.copy(capturedAt = now), now) }
        invalid { store.save(nsd.copy(endpoints = listOf(nsd.endpoints.single().copy(firstSeenAt = now + 1, lastSeenAt = now))), now) }
        invalid { store.save(nsd.copy(endedAt = now), now) }
        invalid { store.save(NetworkSession("router", now, source = NetworkSource.ROUTER_IMPORT), now) }
        invalid { store.save(nsd.copy(source = NetworkSource.ROUTER_IMPORT, capturedAt = now), now) }
        assertTrue(store.load(now).isEmpty())
    }

    @Test
    fun corruptRowAndMalformedFileArePreservedRatherThanOverwrittenByCheckpoint() {
        val directory = temporary.newFolder()
        val database = File(directory, "network-sessions.json")
        val corruptRow = """{"schemaVersion":1,"sessions":[${Json.encodeToString(session())},{"id":"damaged-secret"}]}"""
        listOf("[ broken secret", corruptRow, """{"schemaVersion":99,"sessions":[]}""").forEach { contents ->
            database.writeText(contents)
            val store = NetworkSessionStore(directory)
            val message = ioFailure { store.load(now) }.message.orEmpty()
            assertFalse(message.contains("secret"))
            ioFailure { store.replace(listOf(session()), now) }
            ioFailure { store.save(session(), now) }
            ioFailure { store.recoverInterrupted(now) }
            assertEquals(contents, database.readText())
            assertEquals(listOf("network-sessions.json"), directory.listFiles()!!.map { it.name })
        }
    }

    @Test
    fun ordinaryUnreadableHistoryDoesNotLookLikeEmptyHistory() {
        val directory = temporary.newFolder()
        val database = File(directory, "network-sessions.json")
        assertTrue(database.mkdir())
        File(database, "private-original").writeText("retained")
        val store = NetworkSessionStore(directory)
        ioFailure { store.load(now) }
        ioFailure { store.replace(listOf(session()), now) }
        assertEquals("retained", File(database, "private-original").readText())
    }

    @Test
    fun atomicWriteFailurePreservesPriorSnapshotAndRemovesPrivateTemporaryFile() {
        val directory = temporary.newFolder()
        val original = session()
        NetworkSessionStore(directory).save(original, now)
        val originalBytes = File(directory, "network-sessions.json").readBytes()
        val broken = NetworkSessionStore(directory) { temporary, destination ->
            assertTrue(temporary.isFile)
            assertEquals(directory, temporary.parentFile)
            assertTrue(destination.readBytes().contentEquals(originalBytes))
            throw IOException("simulated replace failure")
        }
        ioFailure { broken.replace(listOf(session("new")), now) }
        ioFailure { broken.recoverInterrupted(now) }
        assertTrue(File(directory, "network-sessions.json").readBytes().contentEquals(originalBytes))
        assertEquals(listOf(original), NetworkSessionStore(directory).load(now))
        assertFalse(directory.listFiles()!!.any { it.name.startsWith(".network-sessions-") })
    }

    @Test
    fun individualDeletionAndExplicitClearRemoveAllStoreOwnedPrivateFiles() {
        val directory = temporary.newFolder()
        val store = NetworkSessionStore(directory)
        store.replace(listOf(session("one"), session("two")), now)
        File(directory, ".network-sessions-abandoned.tmp").writeText("private abandoned identifiers")
        store.delete("one")
        assertEquals(listOf("two"), store.load(now).map { it.id })
        assertFalse(File(directory, "network-sessions.json").readText().contains("\"one\""))
        assertFalse(directory.listFiles()!!.any { it.name.startsWith(".network-sessions-") })
        File(directory, "network-sessions.json").writeText("corrupt private identifiers")
        File(directory, ".network-sessions-abandoned.tmp").writeText("unfinished private identifiers")
        store.deleteAll()
        assertTrue(directory.listFiles()!!.isEmpty())
        assertTrue(NetworkSessionStore(directory).load(now).isEmpty())
    }

    @Test
    fun multipleStoreInstancesUseOneLockForConcurrentIndependentSaves() {
        val directory = temporary.newFolder()
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = (0..9).map { index -> Thread {
            try { NetworkSessionStore(directory).save(session("s-$index"), now) } catch (error: Throwable) { failures.add(error) }
        } }
        threads.forEach(Thread::start)
        threads.forEach { it.join(10_000); assertFalse(it.isAlive) }
        assertTrue(failures.isEmpty())
        assertEquals((0..9).map { "s-$it" }.toSet(), NetworkSessionStore(directory).load(now).map { it.id }.toSet())
    }

    private fun session(id: String = "one", startedAt: Long = now) = NetworkSession(id, startedAt,
        endpoints = listOf(NetworkEndpoint("r-$id", "_http._tcp.", startedAt, startedAt, NetworkSource.NSD,
            instanceName = "Service $id", hostnames = listOf("device.local"), addresses = listOf("192.168.1.23"), port = 80)))

    private fun invalid(block: () -> Unit): IllegalArgumentException {
        try { block(); fail("Expected invalid snapshot to be rejected") } catch (error: IllegalArgumentException) { return error }
        throw AssertionError("Expected invalid snapshot to be rejected")
    }

    private fun ioFailure(block: () -> Unit): IOException {
        try { block(); fail("Expected storage failure") } catch (error: IOException) { return error }
        throw AssertionError("Expected storage failure")
    }
}
