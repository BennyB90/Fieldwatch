package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class BoundedFieldDecodeCacheTest {
    @Test
    fun rotatingPayloadsEvictOldEntriesAndKeepRecentlyUsedPayloads() {
        val cache = BoundedFieldDecodeCache<String>(2)
        var decodes = 0
        fun get(key: String) = cache.getOrPut(key) { decodes++; emptyList() }
        val first = get("first-position")
        get("second-position")
        assertSame(first, get("first-position"))
        get("third-position")
        assertSame(first, get("first-position"))
        assertEquals(3, decodes)
        get("second-position") // evicted, so it must be decoded again
        assertEquals(4, decodes)
    }

    @Test
    fun cachedFieldsCannotBeChangedByTheProducerOrConsumer() {
        val cache = BoundedFieldDecodeCache<String>(2)
        val source = mutableListOf(DecodedFieldValue("f", "Fleet", "id", "ID", "value", 0, 1))
        val cached = cache.getOrPut("payload") { source }
        source.clear()
        assertEquals(1, cached.size)
        try {
            (cached as MutableList<DecodedFieldValue>).clear()
            fail("Cached field snapshots must be immutable")
        } catch (_: UnsupportedOperationException) { }
        assertSame(cached, cache.getOrPut("payload") { fail("Unexpected second decode"); emptyList() })
    }
}
