package app.fieldwatch.sweep.spaces

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpaceControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = AtomicLong(4000)
    private val history = mutableListOf(scan("base", 1000), scan())
    private val dir by lazy { temporary.newFolder("spaces") }
    private val shares by lazy { temporary.newFolder("shares") }
    private fun controller() = SpaceController(SpaceStore(dir), SpacePdfExporter(shares), { history.toList() }, scope) { clock.incrementAndGet() }
    @After fun finish() { scope.cancel() }

    @Test fun queuedMutationsPublishInOrderAndNeverResurrectADeletedSpace() = runBlocking {
        val c = controller()
        c.initialize().await()
        val id = c.create(" My car ", SpaceKind.VEHICLE).await()
        assertEquals("My car", c.state.value.spaces.single().name)
        val first = c.rename(id, "First")
        val second = c.rename(id, "Second")
        first.await()
        second.await()
        assertEquals("Second", c.state.value.spaces.single().name)
        val deleted = c.delete(id)
        val late = c.rename(id, "Late")
        deleted.await()
        assertTrue(runCatching { late.await() }.isFailure)
        assertTrue(SpaceStore(dir).load().isEmpty())
    }

    @Test fun archivedEvidenceAndDigestSurviveScanRotationBaselineReplacementRenameAndRestart() = runBlocking {
        val c = controller()
        val id = c.create("My office", SpaceKind.OFFICE).await()
        c.setBaseline(id, "base").await()
        val before = c.state.value.spaces.single()
        c.saveInspection(before, "current", "log", 3000, checked(SpaceKind.OFFICE)).await()
        val saved = c.state.value.spaces.single().inspections.single()
        val text = c.export(id, "log", SpaceExportFormat.TEXT, true, true).await().readText()
        assertTrue(text.contains(SECRET_NOTE) && text.contains(SSID))
        c.rename(id, "Different office").await()
        history.clear()
        history.add(scan("replacement", 8000, emptyList()))
        c.setBaseline(id, "replacement").await()
        val restart = controller()
        restart.initialize().await()
        assertEquals(saved, restart.state.value.spaces.single().inspections.single())
        assertEquals(text, restart.export(id, "log", SpaceExportFormat.TEXT, true, true).await().readText())
        assertEquals(saved.reportSha256, SpaceReports.report(saved).sha256)
        restart.delete(id).await()
        assertTrue(shares.listFiles()!!.isEmpty())
        assertTrue(runCatching { restart.export(id, "log", SpaceExportFormat.JSON, false, false).await() }.isFailure)
    }

    @Test fun allowsOnlyComparableExpectedAndRejectsStaleReviewOrMissingScan() = runBlocking {
        val c = controller()
        val id = c.create("Room", SpaceKind.ROOM).await()
        val stale = c.state.value.spaces.single()
        assertTrue(runCatching { c.markExpected(id, "current", "BLE:$ADDRESS").await() }.isFailure)
        c.markExpected(id, "current", "WIFI:$ADDRESS").await()
        c.markExpected(id, "current", "WIFI:$ADDRESS").await()
        assertEquals(1, c.state.value.spaces.single().expectedDevices.size)
        assertTrue(runCatching { c.saveInspection(stale, "current", "log", 3000, checked()).await() }.isFailure)
        c.removeExpected(id, "WIFI:$ADDRESS").await()
        assertTrue(c.state.value.spaces.single().expectedDevices.isEmpty())
        history.add(scan("duplicate", findings = listOf(finding(), finding())))
        assertTrue(runCatching { c.markExpected(id, "duplicate", "WIFI:$ADDRESS").await() }.isFailure)
        history.clear()
        assertTrue(runCatching { c.saveInspection(stale, "current", "log", 3000, checked()).await() }.isFailure)
    }

    @Test fun rejectsDemosAndIncompleteHumanChecklistAndArchivesOnlyThirtyDistinctSaves() = runBlocking {
        val c = controller()
        val id = c.create("Car", SpaceKind.VEHICLE).await()
        val saved = c.state.value.spaces.single()
        history.add(scan("demo").copy(demo = true))
        assertTrue(runCatching { c.setBaseline(id, "demo").await() }.isFailure)
        val template = SpaceChecklists.forKind(SpaceKind.VEHICLE)
        assertEquals(6, template.size)
        assertTrue(runCatching { c.saveInspection(saved, "current", "log", 3000, template).await() }.isFailure)
        val checked = checked(SpaceKind.VEHICLE)
        for (index in 0..30) c.saveInspection(saved, "current", "log$index", 3000, checked).await()
        c.saveInspection(saved, "current", "log30", 3000, checked).await()
        val ids = c.state.value.spaces.single().inspections.map { it.id }
        assertEquals((30 downTo 1).map { "log$it" }, ids)
    }

    @Test fun failureRequiresExplicitClearAndQueueCanRecoverWithoutOverwritingOriginal() = runBlocking {
        val c = controller()
        val damaged = File(dir, "spaces.json").apply { writeText("broken") }
        assertTrue(runCatching { c.initialize().await() }.isFailure)
        assertNotNull(c.state.value.readError)
        assertTrue(runCatching { c.create("New", SpaceKind.ROOM).await() }.isFailure)
        assertEquals("broken", damaged.readText())
        c.clear().await()
        assertNull(c.state.value.readError)
        c.create("Recovered", SpaceKind.ROOM).await()
        assertEquals("Recovered", c.state.value.spaces.single().name)
    }

    @Test fun sharesAreBoundedExpiredAndDeletedOnlyForTheirOwner() {
        val exporter = SpacePdfExporter(shares)
        val report = SpaceReports.report(inspection())
        val unrelated = File(shares, "other-owner.txt").apply { writeText("keep") }
        val expired = exporter.export("a", report, SpaceExportFormat.JSON).apply { setLastModified(1000) }
        repeat(10) { exporter.export("a", report, SpaceExportFormat.TEXT) }
        val other = exporter.export("a2", report, SpaceExportFormat.JSON)
        assertFalse(expired.exists())
        assertEquals(8, shares.listFiles()!!.count { it.name.startsWith("inspection-") })
        exporter.clear("a")
        assertTrue(other.exists())
        assertEquals(report.json(), other.readText())
        exporter.clear()
        assertEquals(listOf(unrelated), shares.listFiles()!!.toList())
    }

    @Test fun changedArchiveContentCannotBeExportedUnderAnOldDigest() = runBlocking {
        val c = controller()
        val id = c.create("Unit 4B", SpaceKind.RENTAL_UNIT).await()
        c.saveInspection(c.state.value.spaces.single(), "current", "log", 3000, checked()).await()
        SpaceStore(dir).update { rows -> rows.map { space ->
            space.copy(inspections = space.inspections.map { it.copy(completedAt = it.completedAt + 900) })
        } }
        assertTrue(runCatching { c.export(id, "log", SpaceExportFormat.JSON, true, true).await() }.isFailure)
        assertTrue(shares.listFiles()!!.isEmpty())
    }

    @Test fun tamperedPrivateChecklistNoteOrDroneIdInSpaceNameIsCaught() = runBlocking {
        val c = controller()
        history.add(scan("droneScan", 2500, listOf(finding().copy(name = "Guest\u200BWifi ", droneId = "UAS-12345"))))
        val id = c.create("Room UAS-12345", SpaceKind.RENTAL_UNIT).await()
        c.saveInspection(c.state.value.spaces.single(), "droneScan", "logDrone", 3000, checked()).await()
        val saved = c.state.value.spaces.single().inspections.single()
        assertEquals("[space name withheld]", saved.redactedSpaceName)
        assertEquals(64, saved.archiveSha256.length)
        SpaceStore(dir).update { rows -> rows.map { space ->
            space.copy(inspections = space.inspections.map { log ->
                log.copy(checklist = log.checklist.mapIndexed { idx, item ->
                    if (idx == 0) item.copy(note = "Tampered private note") else item
                })
            })
        } }
        assertTrue(runCatching { c.export(id, "logDrone", SpaceExportFormat.JSON, true, true).await() }.isFailure)
        assertTrue(runCatching { c.export(id, "logDrone", SpaceExportFormat.TEXT, false, false).await() }.isFailure)
    }
}
