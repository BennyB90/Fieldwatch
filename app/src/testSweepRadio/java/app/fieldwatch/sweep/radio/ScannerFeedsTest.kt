package app.fieldwatch.sweep.radio

import java.io.File
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScannerFeedsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun normalizedAddressKeepsSignedPathAndQueryExactly() {
        assertEquals(
            "https://example.test/audio%2Flive?token=a%2Bb%3D&channel=7&next=%2Fstream",
            ScannerFeedValidator.normalizeUrl("  HTTPS://EXAMPLE.TEST:443/audio%2Flive?token=a%2Bb%3D&channel=7&next=%2Fstream  "),
        )
        assertEquals("https://example.test/", ScannerFeedValidator.normalizeUrl("https://example.test"))
    }

    @Test
    fun operatorAuthorizedPrivateLanAndIpv6StreamsAreSupported() {
        assertEquals("https://192.168.1.20:8443/live", ScannerFeedValidator.normalizeUrl("https://192.168.1.20:8443/live"))
        assertEquals("https://scanner.local/live", ScannerFeedValidator.normalizeUrl("https://SCANNER.LOCAL/live"))
        assertEquals("https://[fd00::1]:8443/live", ScannerFeedValidator.normalizeUrl("https://[FD00::1]:8443/live"))
    }

    @Test
    fun unsafeUrlsAreRejectedWithoutRepeatingTheirContents() {
        val unsafe = listOf(
            "http://secret.example/live",
            "https://user:secret@example.test/live",
            "https://example.test/live#secret",
            "https://example.test/live?token=secret\n",
            "https://example.test/live?token=secret%0D%0A",
            "https://example.test:0/secret",
            "https://example.test:65536/secret",
            "https://example.test:/secret",
            "https:secret",
            "//example.test/secret",
            "https://example.test/secret path",
        )
        unsafe.forEach { address ->
            val message = invalid { ScannerFeedValidator.normalizeUrl(address) }.message.orEmpty()
            assertFalse(message.contains("secret"))
            assertFalse(message.contains(address))
            assertTrue(message.isNotBlank())
        }
    }

    @Test
    fun directUnsupportedProviderEndpointsCannotBeAdded() {
        listOf(
            "https://broadcastify.com/listen/feed/1",
            "https://audio3.broadcastify.com/live",
            "https://BROADCASTIFY.COM./live",
            "https://relay.radioreference.com/live",
            "https://radioreference.com/live",
        ).forEach { address ->
            assertTrue(invalid { ScannerFeedValidator.normalizeUrl(address) }.message.orEmpty().contains("not supported"))
        }
        assertEquals("https://my-radio.example/live", ScannerFeedValidator.normalizeUrl("https://my-radio.example/live"))
    }

    @Test
    fun missingUnsafeAndOverlongIdentifiersAndNamesAreRejected() {
        listOf("", "../escape", "has spaces", "x".repeat(129)).forEach { id -> invalid { ScannerFeedValidator.validate(feed().copy(id = id)) } }
        invalid { ScannerFeedValidator.validate(feed().copy(name = "  ")) }
        invalid { ScannerFeedValidator.validate(feed().copy(name = "x".repeat(121))) }
        invalid { ScannerFeedValidator.validate(feed().copy(name = "Scanner\u202Ename")) }
        invalid { ScannerFeedValidator.normalizeUrl("https://example.test/" + "x".repeat(2_048)) }
    }

    @Test
    fun duplicateIdsAndMoreThan200FeedsCannotOverwriteSavedPreferences() {
        val directory = temporary.newFolder()
        val store = ScannerFeedStore(directory)
        val preferences = ScannerPreferences(true, listOf(feed()))
        store.save(preferences)
        invalid { store.save(ScannerPreferences(true, listOf(feed(), feed().copy(name = "Another")))) }
        invalid { store.save(ScannerPreferences(true, (0..200).map { feed().copy(id = "feed-$it") })) }
        assertEquals(preferences, ScannerFeedStore(directory).load())
    }

    @Test
    fun consentFavoriteAndFeedMetadataSurviveRestartAndExplicitConsentRevocation() {
        val directory = temporary.newFolder()
        val store = ScannerFeedStore(directory)
        val configured = ScannerFeed("one", "  My scanner  ", "HTTPS://EXAMPLE.TEST:443/live?token=a%2Bb", " West region ", " Amateur radio ", true)
        store.save(ScannerPreferences(networkConsent = true, feeds = listOf(configured)))
        val loaded = ScannerFeedStore(directory).load()
        assertEquals(true, loaded.networkConsent)
        assertEquals(listOf(configured.copy(name = "My scanner", url = "https://example.test/live?token=a%2Bb", region = "West region", category = "Amateur radio")), loaded.feeds)
        store.save(loaded.copy(networkConsent = false))
        val revoked = ScannerFeedStore(directory).load()
        assertFalse(revoked.networkConsent)
        assertEquals(loaded.feeds, revoked.feeds)
    }

    @Test
    fun explicitResetRemovesCorruptOriginalAndRevokesNetworkConsent() {
        val directory = temporary.newFolder()
        val store = ScannerFeedStore(directory)
        store.save(ScannerPreferences(true, listOf(feed())))
        File(directory, ".scanner-feeds-abandoned.tmp").writeText("unfinished private stream")
        store.deleteAll()
        assertEquals(ScannerPreferences(), ScannerFeedStore(directory).load())
        assertTrue(directory.listFiles()!!.isEmpty())
        File(directory, "scanner-preferences.json").writeText("corrupt original")
        store.deleteAll()
        store.save(ScannerPreferences(false, listOf(feed())))
        assertEquals(listOf(feed()), store.load().feeds)
    }

    @Test
    fun corruptContentsArePreservedAndCannotBeSilentlyOverwrittenBySave() {
        val directory = temporary.newFolder()
        val database = File(directory, "scanner-preferences.json")
        val original = "{\"networkConsent\":true,\"feeds\":[{\"id\":\"one\",\"url\":\"private-secret\"}]}"
        database.writeText(original)
        val store = ScannerFeedStore(directory)
        assertTrue(ioFailure { store.load() }.message.orEmpty().contains("reset"))
        val message = ioFailure { store.save(ScannerPreferences(false, listOf(feed()))) }.message.orEmpty()
        assertFalse(message.contains("private-secret"))
        assertEquals(original, database.readText())
    }

    @Test
    fun malformedJsonAndInvalidRetainedIdsBothRequireExplicitReset() {
        val directory = temporary.newFolder()
        val database = File(directory, "scanner-preferences.json")
        listOf("[{ broken", "[]", Json.encodeToString(ScannerPreferences(true, listOf(feed().copy(id = ""))))).forEach { original ->
            database.writeText(original)
            ioFailure { ScannerFeedStore(directory).load() }
            ioFailure { ScannerFeedStore(directory).save(ScannerPreferences()) }
            assertEquals(original, database.readText())
        }
    }

    @Test
    fun oversizedRetainedFileCannotBeOverwrittenWithoutReset() {
        val directory = temporary.newFolder()
        val database = File(directory, "scanner-preferences.json")
        database.writeBytes(ByteArray(1_048_577) { 'x'.code.toByte() })
        ioFailure { ScannerFeedStore(directory).load() }
        ioFailure { ScannerFeedStore(directory).save(ScannerPreferences()) }
        assertEquals(1_048_577L, database.length())
    }

    @Test
    fun atomicReplacementFailurePreservesConsentAndPriorFeeds() {
        val directory = temporary.newFolder()
        val original = ScannerPreferences(true, listOf(feed()))
        ScannerFeedStore(directory).save(original)
        val before = File(directory, "scanner-preferences.json").readBytes()
        val broken = ScannerFeedStore(directory) { temporary, destination ->
            assertTrue(temporary.isFile)
            assertEquals(directory, temporary.parentFile)
            assertTrue(destination.readBytes().contentEquals(before))
            throw IOException("replacement unavailable")
        }
        ioFailure { broken.save(ScannerPreferences(false, listOf(feed().copy(name = "Changed")))) }
        assertEquals(original, ScannerFeedStore(directory).load())
        assertTrue(File(directory, "scanner-preferences.json").readBytes().contentEquals(before))
        assertFalse(directory.listFiles()!!.any { it.name.startsWith(".scanner-feeds-") })
    }

    private fun feed() = ScannerFeed("one", "My scanner", "https://example.test/live")

    private fun invalid(block: () -> Unit): IllegalArgumentException {
        try { block(); fail("Expected invalid feed to be rejected") } catch (error: IllegalArgumentException) { return error }
        throw AssertionError("Expected invalid feed to be rejected")
    }

    private fun ioFailure(block: () -> Unit): IOException {
        try { block(); fail("Expected storage failure") } catch (error: IOException) { return error }
        throw AssertionError("Expected storage failure")
    }
}
