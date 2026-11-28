package app.fieldwatch.sweep.spaces

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpaceStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun roundTripCreatesPrivateDirectoryAndFile() {
        val dir = File(temporary.root, "spaces")
        val store = SpaceStore(dir)
        val saved = space().copy(baseline = SpaceAnalyzer.baseline(scan()), inspections = listOf(inspection()))
        assertTrue(store.load().isEmpty())
        store.update { listOf(saved) }
        assertEquals(listOf(saved), SpaceStore(dir).load())
        val file = dir.listFiles()!!.single()
        if (Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java) != null) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.toPath())))
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        } else assertTrue(file.canRead() && file.canWrite() && dir.canWrite())
    }

    @Test fun damagedInvalidUtf8AndFutureVersionsSurviveEveryNormalWrite() {
        val dir = temporary.newFolder()
        val db = File(dir, "spaces.json")
        val store = SpaceStore(dir)
        listOf("{broken".toByteArray(), byteArrayOf(0xC3.toByte(), 0x28), """{"version":2,"spaces":[]}""".toByteArray(),
            """{"version":1,"spaces":[{}]}""".toByteArray()).forEach { bytes ->
            db.writeBytes(bytes)
            assertThrows(SpaceHistoryUnreadable::class.java) { store.load() }
            assertThrows(SpaceHistoryUnreadable::class.java) { store.update { listOf(space()) } }
            assertArrayEquals(bytes, db.readBytes())
        }
        val unrelated = File(dir, "unrelated.txt").apply { writeText("keep") }
        store.clear()
        assertTrue(store.load().isEmpty())
        assertTrue(unrelated.exists())
        store.update { listOf(space()) }
        assertEquals(space(), store.load().single())
    }

    @Test fun failedReplacementKeepsOriginalAndCleansTemporaryFiles() {
        val dir = temporary.newFolder()
        SpaceStore(dir).update { listOf(space()) }
        val original = File(dir, "spaces.json").readBytes()
        val broken = SpaceStore(dir) { temp, target ->
            assertEquals(dir, temp.parentFile)
            assertTrue(temp.length() > 0)
            assertArrayEquals(original, target.readBytes())
            throw IOException("simulated replacement failure")
        }
        assertThrows(IOException::class.java) { broken.update { listOf(space().copy(name = "edited")) } }
        assertArrayEquals(original, File(dir, "spaces.json").readBytes())
        File(dir, ".spaces-orphan.tmp").writeText("abandoned")
        assertEquals(space(), broken.load().single())
        assertEquals(listOf("spaces.json"), dir.list()!!.toList())
    }

    @Test fun nestedLimitsAndDuplicateIdsRejectWholeMutation() {
        val store = SpaceStore(temporary.newFolder())
        val original = space()
        store.update { listOf(original) }
        val device = SpaceAnalyzer.expected(finding(), 100)
        val baseline = SpaceAnalyzer.baseline(scan())
        val log = inspection()
        listOf(List(26) { original.copy(id = "s$it") }, listOf(original, original),
            listOf(original.copy(expectedDevices = List(251) { device })),
            listOf(original.copy(baseline = baseline.copy(devices = List(501) { baseline.devices.single() }))),
            listOf(original.copy(inspections = List(31) { log.copy(id = "log$it") })),
            listOf(original.copy(id = "../outside"))).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { store.update { invalid } }
            assertEquals(listOf(original), store.load())
        }
    }

    @Test fun concurrentStoreInstancesPreserveIndependentUpdates() {
        val dir = temporary.newFolder()
        val pool = Executors.newFixedThreadPool(3)
        try {
            val work = (1..15).map { n -> pool.submit { SpaceStore(dir).update { it + space().copy(id = "s$n") } } }
            work.forEach { it.get() }
            assertEquals((1..15).map { "s$it" }.toSet(), SpaceStore(dir).load().map { it.id }.toSet())
        } finally { pool.shutdownNow() }
    }
}
