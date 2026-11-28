package app.fieldwatch.sweep.radio.monas

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MonasdHttpClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private val base = "https://lab.invalid:8443/"
    private val health = """{"radio":"Lab","streaming":true,"stale":false,"sample_rate_hz":20000000,"view":"NET","lost_samples":{"all":17,"driver":16,"monas":1}}"""
    private val testimony = """"testimony":{"at_unix_ms":42,"stale":false,"feed_loss":false,"absent":null}"""
    private val device = """{"address":"Apple #17","kind":"rpa","packets":10,"company":"Example","name":null,
        "crc_pass_pct":90,"first_seen_s":18,"last_seen_s":1,"mean_snr_db":{"value":{"value":10.1,"sigma":1}},
        "crystal_offset_ppm":{"value":-1.62,"sigma":0.015},"modulation_index":{"absent":"not measured"},
        "advertising":{"interval_ms":null}}"""
    private fun json(s: String) = MonasdParser.decode(s.toByteArray())

    private class Fake(val respond: (MonasdRequest) -> MonasdResponse) : MonasdTransport {
        val routes = mutableListOf<String>()
        override fun get(request: MonasdRequest): MonasdResponse { routes += request.url.file; return respond(request) }
        override fun cancelRequests() {}
        override fun close() {}
    }

    @Test fun acceptsHttpsProxyPrefixesAndPrivateHostsRejectsAmbiguousPathsAndProviders() {
        assertEquals("https://[fd00::1]:8443/proxy/", MonasdServerAddress.normalizeBaseUrl("https://[fd00::1]:8443/proxy"))
        assertEquals("https://192.168.1.1/", MonasdServerAddress.normalizeBaseUrl("https://192.168.1.1:443"))
        assertEquals("https://receiver.net.ts.net:65535/", MonasdServerAddress.normalizeBaseUrl("HTTPS://Receiver.Net.ts.net:65535"))
        val bad = listOf("http://lab", "https://u:p@lab", "https://lab/?q", "https://lab/#f", "https://lab/../v1",
            "https://lab/a/./b", "https://lab/%2e%2e/", "https://lab/a%2Fb", "https://lab/a%5cb", "https://lab/%0a",
            "https://lab/a;param/b", "https://lab/a%3bb",
            "https://lab/%252e%252e", "https://lab/%E2%80%AE", "https://lab:0", "https://lab:65536",
            "https://x.broadcastify.com", "https://RadioReference.com.", "https://broadcastify.net")
        bad.forEach { assertThrows(it, MonasException::class.java) { MonasdServerAddress.normalizeBaseUrl(it) } }
    }

    @Test fun pinFormatsRequireExactlyOneSha256Digest() {
        val bytes = ByteArray(32) { it.toByte() }
        val pairs = bytes.map { "%02x".format(it) }
        val base64 = Base64.getEncoder().encodeToString(bytes)
        for (raw in listOf(pairs.joinToString(""), pairs.joinToString(":"), "sha256/$base64", "sha256/" + base64.trimEnd('='))) {
            assertArrayEquals(bytes, MonasdServerAddress.parseSpkiPin(raw))
        }
        assertNull(MonasdServerAddress.parseSpkiPin(""))
        listOf("ab", "a".repeat(63), "a".repeat(65), "sha256/YQ==", "sha256/" + "a".repeat(44),
            pairs.drop(1).joinToString(":"), pairs.joinToString("-")).forEach {
            assertThrows(MonasException::class.java) { MonasdServerAddress.parseSpkiPin(it) }
        }
    }

    @Test fun parsesFivePublishedRoutesWithAbsentMaskedAndUncertainReadings() {
        val fake = Fake { request ->
            val body = when (request.url.file) {
                "/v1/health" -> health
                "/v1/provenance" -> """{"reference":"unreferenced, no reference established","addresses":"masked","mode":"NET / LOCK","session":"01:02:03"}"""
                "/v1/radios" -> """[{"label":"HackRF","backend":"hackrf","serial":"private","open":true,"verified":false}]"""
                "/v1/net/census" -> """{$testimony,"devices":[$device]}"""
                "/v1/net/ble?since=7" -> """{$testimony,"packets":[{"seq":8,"address":"Apple #17","channel":37,"crc_ok":true,"snr_db":{"value":12},"cfo_ppm":null,"mod_index":{"value":0.50,"sigma":0.01}}]}"""
                else -> error("Unexpected route")
            }
            MonasdResponse(200, "application/json; charset=utf-8", body.toByteArray())
        }
        MonasdHttpClient(base, charArrayOf(), "", { true }, fake).use {
            val s = it.snapshot(7)
            assertEquals(listOf("/v1/health", "/v1/provenance", "/v1/radios", "/v1/net/census", "/v1/net/ble?since=7", "/v1/health"), fake.routes)
            assertEquals(17L, s.health.lostSamples)
            assertEquals(3723L, s.provenance.sessionSeconds)
            assertEquals(MonasReferenceTier.RELATIVE, s.provenance.tier)
            assertEquals("private", s.radios.single().serial)
            val row = s.census.devices.single()
            assertTrue(row.maskedAddress)
            assertNull(row.modulationIndex)
            assertEquals(MonasSigma(10.1, 1.0), row.meanSnrDb)
            assertEquals(MonasSigma(-1.62, 0.015), row.crystalOffsetPpm)
            assertEquals(8L, s.ble.packets.single().seq)
            assertEquals(MonasSigma(0.5, 0.01), s.ble.packets.single().modulationIndex)
        }
        assertEquals("offline", MonasdParser.census(json("""{"absent":"offline"}""")).testimony.absent)
        assertEquals("no net frame", MonasdParser.census(
            json("""{"testimony":{"at_unix_ms":null,"stale":true,"feed_loss":false,"absent":null},"absent":"no net frame"}""")
        ).testimony.absent)
        assertEquals(901L, MonasdParser.provenance(json("""{"reference":{"provenance":"traceable","age_s":901}}""")).referenceAgeSec)
        val unref = MonasdParser.provenance(json("""{"reference":{"absent":"no reference established"},"duration":"00:10:05","reference_age_s":"45"}"""))
        assertEquals(MonasReferenceTier.RELATIVE, unref.tier)
        assertFalse(unref.expired)
        assertEquals(605L, unref.sessionSeconds)
        assertEquals(45L, unref.referenceAgeSec)
        val expiredRef = MonasdParser.provenance(json("""{"frequency_reference":{"absent":"a traceable reference against WWV expired after 00:15:00"}}"""))
        assertTrue(expiredRef.expired)
        assertEquals(MonasReferenceTier.TRACEABLE, expiredRef.tier)
        val outage = MonasdParser.health(json("""{"radio":null,"view":null,"streaming":false,"stale":true,"sample_rate_hz":{"absent":"stopped"},"feeds":{"net":{"refused_session":3}}}"""))
        assertTrue(outage.stale)
        assertNull(outage.sampleRateHz)
        assertEquals(3L, outage.netRefused)
        val emojiRow = MonasdParser.census(json("""{$testimony,"devices":[${device.replace("\"name\":null", "\"name\":\"Ben's 👨‍👩‍👧 iPhone\\u0000\\r\\n\"")}]}""")).devices.single()
        assertTrue(emojiRow.name!!.contains("Ben's") && emojiRow.name!!.contains("iPhone") && '\u200D' !in emojiRow.name!!)
    }

    @Test fun rejectsInvalidUncertaintyEncodingAndDeepJsonAndBoundsPages() {
        for (sigma in listOf("-1", "NaN", "Infinity", "1e999", "\"1\"")) {
            assertThrows(MonasException::class.java) { MonasdParser.census(json("""{$testimony,"devices":[${device.replace("0.015", sigma)}]}""")) }
        }
        assertThrows(MonasException::class.java) { MonasdParser.decode(byteArrayOf(0x22, 0xc0.toByte(), 0x22)) }
        assertThrows(MonasException::class.java) { MonasdParser.decode(ByteArray(MAX_BODY + 1)) }
        assertThrows(MonasException::class.java) { json("[".repeat(17) + "]".repeat(17)) }
        json("[".repeat(16) + """"[{escaped\"quote}]"""" + "]".repeat(16))
        assertThrows(MonasException::class.java) { MonasdParser.census(json("""{"devices":[]}""")) }
        val census = MonasdParser.census(json("""{$testimony,"devices":[${List(501) { device }.joinToString(",")}]}"""))
        assertEquals(500, census.devices.size); assertEquals(501, census.total)
        val packets = List(201) { """{"seq":$it,"crc_ok":true}""" }.joinToString(",")
        assertEquals(200, MonasdParser.ble(json("""{$testimony,"packets":[$packets]}""")).packets.size)
    }

    @Test fun checksConsentBeforeAndAfterEvenWhenTransportReturnsSensitiveData() {
        var consent = false
        val fake = Fake { consent = false; MonasdResponse(200, "application/json", health.toByteArray()) }
        val token = "private".toCharArray()
        MonasdHttpClient(base, token, "", { consent }, fake).use { client ->
            assertThrows(MonasException::class.java) { client.health() }
            assertTrue(fake.routes.isEmpty())
            consent = true
            assertThrows(MonasException::class.java) { client.health() }
            assertEquals(1, fake.routes.size)
        }
        assertTrue(token.all { it == '\u0000' })
        for (invalid in listOf("s".repeat(257), "\na", "\u200D", String(Character.toChars(0xe0001)))) {
            val owned = invalid.toCharArray()
            assertThrows(MonasException::class.java) { MonasdHttpClient(base, owned, "", { true }, fake) }
            assertTrue(owned.all { it == '\u0000' })
        }
        val failure = Fake { throw IllegalArgumentException("https://lab.invalid/private") }
        MonasdHttpClient(base, charArrayOf(), "", { true }, failure).use { client ->
            val error = assertThrows(MonasException::class.java) { client.health() }
            assertFalse(error.message.orEmpty().contains("lab.invalid"))
            assertNull(error.cause)
        }
    }

    private open class Connection(url: URL, var code: Int = 200, val bytes: ByteArray) : HttpsURLConnection(url) {
        override fun getResponseCode() = code
        override fun getContentType() = "application/json"
        override fun getInputStream() = bytes.inputStream()
        override fun getCipherSuite() = "TLS"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
    }

    @Test fun configuresGetOmitsBlankTokensRejectsRedirectsAndKeepsErrorsOpaque() {
        for (raw in listOf("", "  ", "secret-token")) {
            val wire = Connection(URL(base), 307, "PRIVATE RAW BODY".toByteArray())
            val http = MonasdHttpsTransport(URL(base).host, null, { wire })
            val token = raw.toCharArray()
            MonasdHttpClient(base, token, "", { true }, http).use { client ->
                val error = assertThrows(MonasException::class.java) { client.health() }
                assertFalse(wire.instanceFollowRedirects)
                assertEquals("GET", wire.requestMethod)
                assertEquals(8000, wire.connectTimeout); assertEquals(10000, wire.readTimeout)
                assertEquals("identity", wire.getRequestProperty("Accept-Encoding"))
                assertEquals(if (raw.isBlank()) null else "Bearer $raw", wire.getRequestProperty("Authorization"))
                val safe = listOf(error.toString(), error.message.orEmpty(), MonasdRequest(URL(base)) {}.toString(),
                    MonasdResponse(500, base, wire.bytes).toString()).joinToString()
                listOf("lab.invalid", "secret-token", "PRIVATE RAW BODY").forEach { assertFalse(safe.contains(it)) }
            }
            assertTrue(token.all { it == '\u0000' })
        }
    }

    @Test fun capsActualBytesAndWallDeadlineIncludesTimeToReceiveHeaders() {
        val big = Connection(URL(base), 200, ByteArray(MAX_BODY + 1))
        MonasdHttpsTransport(URL(base).host, null, { big }).use {
            assertThrows(MonasException::class.java) { it.get(MonasdRequest(URL(base)) {}) }
        }
        val release = CountDownLatch(1)
        val slow = object : Connection(URL(base), bytes = byteArrayOf()) {
            override fun getResponseCode(): Int { release.await(10, TimeUnit.SECONDS); return 200 }
        }
        try {
            MonasdHttpsTransport(URL(base).host, null, { slow }, deadlineMs = 100).use {
                assertThrows(MonasException::class.java) { it.get(MonasdRequest(URL(base)) {}) }
            }
        } finally { release.countDown() }
    }

    @Test fun pinnedSelfSignedLoopbackAcceptsMatchingLeafRejectsDifferentPinAndDefaultTrust() {
        // A fresh local certificate with a deliberately different DNS name exercises the field-AP case.
        val store = File(temporary.root, "ephemeral.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool" + if (File.separator == "\\") ".exe" else "")
        val generate = ProcessBuilder(keytool.path, "-genkeypair", "-alias", "lab", "-dname", "CN=receiver.invalid",
            "-ext", "SAN=dns:receiver.invalid", "-keyalg", "EC", "-validity", "1", "-storetype", "PKCS12",
            "-keystore", store.path, "-storepass", "fixture").redirectErrorStream(true).start()
        assertEquals(generate.inputStream.bufferedReader().readText(), 0, generate.waitFor())
        val keys = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "fixture".toCharArray()) } }
        val cert = keys.getCertificate("lab") as X509Certificate
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "fixture".toCharArray()) }
        val ssl = SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
        val server = ssl.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val hits = AtomicInteger()
        val responder = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val reader = socket.getInputStream().bufferedReader()
                        if (reader.readLine() != "GET /v1/health HTTP/1.1") return@use
                        while (!reader.readLine().isNullOrEmpty()) { /* consume the headers */ }
                        hits.incrementAndGet()
                        val bytes = health.toByteArray()
                        val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply { write(headers.toByteArray()); write(bytes); flush() }
                    }
                } catch (_: IOException) { /* the mismatched and untrusted handshakes abort here */ }
            }
        }.apply { isDaemon = true; start() }
        try {
            val address = "https://127.0.0.1:${server.localPort}"
            val pin = "sha256/" + Base64.getEncoder().encodeToString(digest)
            MonasdHttpClient(address, charArrayOf(), pin, { true }).use { assertEquals("Lab", it.health().radio) }
            for (wrong in listOf("", "00".repeat(32))) {
                MonasdHttpClient(address, charArrayOf(), wrong, { true }).use { client ->
                    assertThrows(MonasException::class.java) { client.health() }
                }
            }
            assertEquals(1, hits.get())
            val trust = MonasPinnedTrust("127.0.0.1", digest)
            val session = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SSLSession::class.java)) { _, method, _ ->
                if (method.name == "getPeerCertificates") arrayOf<Certificate>(cert) else null
            } as SSLSession
            assertTrue(trust.hostnameVerifier.verify("127.0.0.1", session))
            assertFalse(trust.hostnameVerifier.verify("receiver.invalid", session))
            val v6Trust = MonasPinnedTrust("[fd00:0:0:0::0001]", digest)
            assertTrue(v6Trust.hostnameVerifier.verify("fd00::1", session))
            assertFalse(v6Trust.hostnameVerifier.verify("fd00::2", session))
        } finally { server.close(); responder.join(3000) }
    }
}
