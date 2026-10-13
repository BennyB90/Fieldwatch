package app.fieldwatch.sweep.radio.rdio

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class RdioHttpClientTest {
    private val config = """{"apiVersion":1,"systems":[{"id":7,"label":"County","talkgroups":[{"id":42,"label":"Dispatch","name":"Fire"}]}]}"""
    private val page = """{"apiVersion":1,"count":3,"limit":2,"offset":0,"nextOffset":2,"calls":[{"id":11,"system":7,"talkgroup":42,"dateTime":"2026-10-12T18:05:00Z"},{"id":10,"system":7,"talkgroup":42,"dateTime":"2026-10-12T18:04:00Z"}]}"""

    @Test fun constructorDoesNotConnectAndConsentBlocksEveryOperation() {
        val transport = FakeTransport(config)
        val client = RdioHttpClient("https://example.test/rdio/", "private-code", { false }, transport)
        assertEquals(0, transport.requests.size)
        listOf<() -> Unit>({ client.config() }, { client.calls() }, { client.audio(11) }).forEach { action -> expectError(action) }
        assertEquals(0, transport.requests.size)
    }

    @Test fun pinIsOnlyInPostBodyAndRoutesStayUnderNormalizedBase() {
        val transport = FakeTransport(config)
        val client = RdioHttpClient("https://EXAMPLE.test:443/rdio", "PRIVATE-PIN", { true }, transport)
        assertEquals("Dispatch", client.config().systems.single().talkgroups.single().label)
        assertEquals("https://example.test/rdio/sweep-rdio/v1/config", transport.requests.single().url)
        assertFalse(transport.requests.single().url.contains("PRIVATE-PIN"))
        assertFalse(transport.requests.single().toString().contains("PRIVATE-PIN"))
        assertEquals("PRIVATE-PIN", Json.parseToJsonElement(transport.bodies.single()).jsonObject["pin"]!!.jsonPrimitive.content)
        assertTrue(transport.requests.single().body.all { it == 0.toByte() })
    }

    @Test fun callsUseBoundedExplicitPaginationAndDoNotPoll() {
        val transport = FakeTransport(page)
        val client = RdioHttpClient("https://example.test", "", { true }, transport)
        val result = client.calls(2)
        assertEquals(listOf(11L, 10L), result.calls.map { it.id })
        assertEquals(2, result.nextOffset)
        assertEquals(1, transport.requests.size)
        val body = Json.parseToJsonElement(transport.bodies.single()).jsonObject
        assertEquals("2", body["limit"]!!.jsonPrimitive.content)
        assertEquals("0", body["offset"]!!.jsonPrimitive.content)
        expectError { client.calls(101) }
        expectError { client.calls(0) }
        expectError { client.calls(offset = -1) }
        assertEquals(1, transport.requests.size)
    }

    @Test fun revokedConsentAfterResponseDoesNotReturnServerData() {
        val allowed = AtomicBoolean(true)
        val transport = FakeTransport(config).also { it.afterPost = { allowed.set(false) } }
        val client = RdioHttpClient("https://example.test", "secret", allowed::get, transport)
        assertTrue(expectError { client.config() }.message!!.contains("Allow internet audio"))
        assertEquals(1, transport.requests.size)
    }

    @Test fun closePermanentlyClosesButCancelKeepsClientReusable() {
        val transport = FakeTransport(config)
        val client = RdioHttpClient("https://example.test", "secret", { true }, transport)
        client.cancelRequests()
        assertEquals(1, transport.cancellations)
        client.config()
        client.close()
        assertTrue(transport.closed)
        expectError { client.config() }
        assertEquals(1, transport.requests.size)
    }

    @Test fun upstreamErrorsAndCredentialsNeverEscapeInExceptions() {
        val transport = FakeTransport("PRIVATE-PIN https://secret.test/private").also { it.status = 401 }
        val client = RdioHttpClient("https://example.test", "PRIVATE-PIN", { true }, transport)
        val denied = expectError { client.config() }
        assertFalse(denied.message!!.contains("PRIVATE-PIN"))
        assertNull(denied.cause)
        transport.failure = IllegalStateException("PRIVATE-PIN logged upstream")
        val failure = expectError { client.config() }
        assertFalse(failure.message!!.contains("PRIVATE-PIN"))
        assertNull(failure.cause)
    }

    @Test fun redirectsAreRejectedRatherThanForwardingCredential() {
        val transport = FakeTransport(config).also { it.status = 302 }
        val client = RdioHttpClient("https://example.test", "private", { true }, transport)
        assertTrue(expectError { client.config() }.message!!.contains("redirected"))
        assertEquals(1, transport.requests.size)
    }

    @Test fun upstreamHtmlCannotMasqueradeAsAnAvailableListenerRestApi() {
        val transport = FakeTransport("<html>Rdio Scanner web app</html>").also { it.mime = "text/html" }
        val client = RdioHttpClient("https://example.test", "", { true }, transport)
        assertTrue(expectError { client.config() }.message!!.contains("HTTP companion"))
    }

    @Test fun hugeBodiesDeepJsonAndInvalidUtf8AreRejected() {
        val transport = FakeTransport(config)
        val client = RdioHttpClient("https://example.test", "", { true }, transport)
        transport.bytes = ByteArray(RdioHttpClient.MAX_JSON_BYTES + 1)
        expectError { client.config() }
        transport.bytes = ("[".repeat(20) + "0" + "]".repeat(20)).toByteArray()
        expectError { client.config() }
        transport.bytes = byteArrayOf(0xC3.toByte(), 0x28)
        expectError { client.config() }
    }

    @Test fun maliciousCallCountsIdsDatesAndPaginationAreRejected() {
        listOf(
            page.replace("\"limit\":2", "\"limit\":99"),
            page.replace("\"nextOffset\":2", "\"nextOffset\":1"),
            page.replace("\"count\":3", "\"count\":0"),
            page.replace("\"id\":10", "\"id\":11"),
            page.replace("\"id\":11", "\"id\":9223372036854775808"),
            page.replace("2026-10-12T18:05:00Z", "not-a-time"),
        ).forEach { bad -> expectError { RdioHttpClient("https://example.test", "", { true }, FakeTransport(bad)).calls(2) } }
    }

    @Test fun emptyPageBeyondPrunedCountIsAValidEndOfBrowsing() {
        val data = """{"apiVersion":1,"count":2,"limit":50,"offset":50,"nextOffset":null,"calls":[]}"""
        val result = RdioHttpClient("https://example.test", "", { true }, FakeTransport(data)).calls(offset = 50)
        assertTrue(result.calls.isEmpty())
        assertNull(result.nextOffset)
    }

    @Test fun selectedAudioIsFetchedOnceAsBytesAndNotAUrl() {
        val wav = ByteArray(44).also { "RIFF".toByteArray().copyInto(it); "WAVE".toByteArray().copyInto(it, 8) }
        val transport = FakeTransport("").also { it.bytes = wav; it.mime = "audio/wav" }
        val audio = RdioHttpClient("https://example.test", "PRIVATE-PIN", { true }, transport).audio(11)
        assertArrayEquals(wav, audio.bytes)
        assertEquals("audio/wav", audio.mimeType)
        assertEquals("https://example.test/sweep-rdio/v1/audio", transport.requests.single().url)
        assertEquals("11", Json.parseToJsonElement(transport.bodies.single()).jsonObject["callId"]!!.jsonPrimitive.content)
        assertEquals(RdioHttpClient.MAX_AUDIO_BYTES, transport.requests.single().maxResponseBytes)
    }

    @Test fun invalidAudioOrUnsupportedMimeCannotBecomeMedia() {
        val transport = FakeTransport("<html>not audio</html>").also { it.mime = "audio/mpeg" }
        val client = RdioHttpClient("https://example.test", "", { true }, transport)
        expectError { client.audio(11) }
        transport.mime = "text/html"
        expectError { client.audio(11) }
        transport.bytes = ByteArray(RdioHttpClient.MAX_AUDIO_BYTES + 1)
        expectError { client.audio(11) }
        expectError { client.audio(0) }
    }

    @Test fun serverAddressesCannotCarryCredentialsOrEscapeBasePath() {
        listOf("http://example.test", "https://user:pass@example.test", "https://example.test?pin=secret", "https://example.test/#secret", "https://example.test/a/../b", "https://example.test/a%2fb", "https://broadcastify.com").forEach {
            expectError { RdioServerAddress.normalizeBaseUrl(it) }
        }
        assertEquals("https://example.test/rdio/", RdioServerAddress.normalizeBaseUrl("https://EXAMPLE.test:443/rdio"))
    }

    private fun expectError(action: () -> Unit): RdioException {
        try { action(); fail("Expected a sanitized RdioException") }
        catch (failure: RdioException) { return failure }
        throw AssertionError("No exception")
    }

    private class FakeTransport(data: String) : RdioTransport {
        var bytes = data.toByteArray()
        var mime = "application/json"
        var status = 200
        var afterPost: () -> Unit = {}
        var failure: Exception? = null
        var closed = false
        var cancellations = 0
        val requests = mutableListOf<RdioRequest>()
        val bodies = mutableListOf<String>()
        override fun post(request: RdioRequest): RdioResponse {
            requests += request
            bodies += request.body.toString(Charsets.UTF_8)
            failure?.let { throw it }
            afterPost()
            return RdioResponse(status, mime, bytes)
        }
        override fun cancelRequests() { cancellations++ }
        override fun close() { closed = true }
    }
}
