// SPDX-License-Identifier: MIT
package app.fieldwatch.sweep.radio.monas

import android.annotation.SuppressLint
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class MonasdRequest internal constructor(val url: URL, internal val authorize: (HttpsURLConnection) -> Unit) {
    override fun toString() = "MonasdRequest(GET)"
}

class MonasdResponse(val status: Int, val mimeType: String?, val body: ByteArray) {
    override fun toString() = "MonasdResponse(status=$status)"
}

interface MonasdTransport : AutoCloseable {
    fun get(request: MonasdRequest): MonasdResponse
    fun cancelRequests()
}

/** Used only when the operator explicitly supplies a 32-byte SHA-256 SPKI pin; otherwise default Android TLS trust applies. */
@SuppressLint("CustomX509TrustManager")
internal class MonasPinnedTrust(host: String, digest: ByteArray) : X509TrustManager {
    private val expected = digest.copyOf()
    private fun checkLeaf(leaf: X509Certificate?) {
        if (leaf == null) throw CertificateException("Missing leaf certificate.")
        leaf.checkValidity()
        val spki = leaf.publicKey?.encoded ?: throw CertificateException("Missing leaf public key.")
        val actual = MessageDigest.getInstance("SHA-256").digest(spki)
        if (!MessageDigest.isEqual(expected, actual)) throw CertificateException("Public-key pin mismatch.")
    }
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty() || authType.isNullOrBlank()) throw CertificateException("Missing server certificate chain.")
        checkLeaf(chain[0])
    }
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit =
        throw CertificateException("Client certificates are unsupported.")
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun sameHost(peer: String?, target: String): Boolean {
        if (peer.isNullOrBlank()) return false
        val cleanPeer = peer.removeSurrounding("[", "]")
        val cleanTarget = target.removeSurrounding("[", "]")
        if (cleanPeer.equals(cleanTarget, ignoreCase = true)) return true
        if (target.startsWith("[") && target.endsWith("]") && ':' in cleanPeer) {
            return runCatching {
                InetAddress.getByName(target) == InetAddress.getByName("[$cleanPeer]")
            }.getOrDefault(false)
        }
        return false
    }

    val hostnameVerifier = HostnameVerifier { peer, session ->
        sameHost(peer, host) &&
            try { checkLeaf(session.peerCertificates.firstOrNull() as? X509Certificate); true }
            catch (_: Exception) { false }
    }
    val socketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(this@MonasPinnedTrust), null)
    }.socketFactory
}

/** The caller's wall deadline includes DNS, TLS, headers and body reads. */
class MonasdHttpsTransport internal constructor(
    host: String, pin: ByteArray?,
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val deadlineMs: Long = 15_000,
) : MonasdTransport {
    private val trust = pin?.let { MonasPinnedTrust(host, it) }
    private val worker = Executors.newSingleThreadExecutor { job -> Thread(job, "Monas HTTPS").apply { isDaemon = true } }
    private val lock = Any()
    private var closed = false
    private var active: HttpsURLConnection? = null
    private var pending: FutureTask<MonasdResponse>? = null
    private var generation = 0L

    override fun get(request: MonasdRequest): MonasdResponse {
        val task = synchronized(lock) {
            if (closed || pending != null) throw MonasException("The metrology connection is busy or closed.")
            val ticket = generation
            FutureTask { read(request, ticket) }.also { pending = it; worker.execute(it) }
        }
        try {
            return task.get(deadlineMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            close()
            throw MonasException("The metrology request timed out.")
        } catch (_: InterruptedException) {
            cancelRequests()
            Thread.currentThread().interrupt()
            throw MonasException("The metrology request was interrupted.")
        } catch (_: CancellationException) {
            throw MonasException("The metrology request was cancelled.")
        } catch (e: ExecutionException) {
            throw e.cause as? MonasException ?: MonasException("HTTPS failed. Check the server, certificate and public-key pin.")
        } finally {
            synchronized(lock) { if (pending === task) pending = null }
        }
    }

    private fun read(request: MonasdRequest, ticket: Long): MonasdResponse {
        val connection = open(request.url)
        try {
            synchronized(lock) {
                if (closed || generation != ticket) throw MonasException("The metrology request was cancelled.")
                active = connection
            }
            connection.apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                useCaches = false
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "identity")
                trust?.let { sslSocketFactory = it.socketFactory; hostnameVerifier = it.hostnameVerifier }
            }
            request.authorize(connection)
            val status = connection.responseCode
            if (status != 200) return MonasdResponse(status, null, byteArrayOf())
            val encoding = connection.contentEncoding
            if (!encoding.isNullOrEmpty() && !encoding.equals("identity", true)) throw MonasException("Unsupported response encoding.")
            if (connection.contentLengthLong > MAX_BODY) tooLarge()
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw MonasException("The metrology request was cancelled.")
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count > MAX_BODY - bytes.size()) tooLarge()
                    bytes.write(buffer, 0, count)
                }
            }
            return MonasdResponse(status, connection.contentType, bytes.toByteArray())
        } finally {
            synchronized(lock) { if (active === connection) active = null }
            connection.disconnect()
        }
    }

    override fun cancelRequests() {
        val connection = synchronized(lock) {
            generation++
            pending?.cancel(true)
            active
        }
        // Some platform providers wait for an in-progress read in disconnect().
        if (connection != null) Thread({ connection.disconnect() }, "Monas disconnect").apply { isDaemon = true }.start()
    }

    override fun close() {
        synchronized(lock) { closed = true }
        cancelRequests()
        worker.shutdownNow()
    }
}

internal const val MAX_BODY = 1_048_576
private fun tooLarge(): Nothing = throw MonasException("The metrology response exceeds 1 MiB.")

/** Takes ownership of token, including on failed construction. Never retain a header string. */
class MonasdHttpClient(
    baseUrl: String, private val token: CharArray, spkiPin: String,
    private val allowed: () -> Boolean, transport: MonasdTransport? = null,
) : AutoCloseable {
    private val address: String
    private val http: MonasdTransport
    @Volatile private var closed = false

    init {
        try {
            address = MonasdServerAddress.normalizeBaseUrl(baseUrl)
            val digest = MonasdServerAddress.parseSpkiPin(spkiPin)
            if (!validToken(token)) throw MonasException("Use a token of at most 256 characters without control or format characters.")
            http = transport ?: MonasdHttpsTransport(URL(address).host, digest)
        } catch (e: Exception) {
            token.fill('\u0000')
            throw e as? MonasException ?: MonasException("The secure metrology connection could not be initialized.")
        }
    }

    private fun checkAllowed() {
        if (closed || !allowed()) throw MonasException("Metrology needs foreground network consent and authorization for this server.")
    }

    private fun json(path: String): kotlinx.serialization.json.JsonElement {
        checkAllowed()
        val request = MonasdRequest(URL(address + "v1/" + path)) { connection ->
            synchronized(token) {
                checkAllowed()
                var start = 0
                var end = token.size
                while (start < end && token[start] == ' ') start++
                while (end > start && token[end - 1] == ' ') end--
                if (start < end) {
                    val len = end - start
                    val header = CharArray(7 + len)
                    "Bearer ".toCharArray(header)
                    token.copyInto(header, destinationOffset = 7, startIndex = start, endIndex = end)
                    try { connection.setRequestProperty("Authorization", String(header)) }
                    finally { header.fill('\u0000') }
                }
            }
        }
        val response = try { http.get(request) }
        catch (e: MonasException) { throw e }
        catch (_: Exception) { throw MonasException("The metrology request failed.") }
        finally { checkAllowed() }
        when (response.status) {
            200 -> Unit
            in 300..399 -> throw MonasException("The server redirected the request. Enter its final HTTPS address.")
            401, 403 -> throw MonasException("Access denied. Check the operator’s read access token.")
            else -> throw MonasException("The server could not supply this metrology reading.")
        }
        if (response.body.size > MAX_BODY) tooLarge()
        if (!response.mimeType?.substringBefore(';')?.trim().equals("application/json", true)) {
            throw MonasException("The server did not return a JSON metrology reading.")
        }
        return MonasdParser.decode(response.body)
    }

    fun health() = MonasdParser.health(json("health"))
    fun provenance() = MonasdParser.provenance(json("provenance"))
    fun radios() = MonasdParser.radios(json("radios"))
    fun census(masked: Boolean = false) = MonasdParser.census(json("net/census"), masked)
    fun ble(since: Long): MonasPacketPage {
        if (since < 0) throw MonasException("The packet sequence must be nonnegative.")
        return MonasdParser.ble(json("net/ble?since=$since"))
    }

    fun snapshot(since: Long): MonasSnapshot {
        val start = health()
        val provenance = provenance()
        val radios = radios()
        val census = census(provenance.masked)
        val page = ble(since)
        val end = health()
        return MonasSnapshot(end, provenance, radios, census, page, continuityIssue = MonasdAnalyzer.healthChange(start, end))
    }

    fun cancelRequests() = http.cancelRequests()
    override fun close() {
        synchronized(token) { closed = true; token.fill('\u0000') }
        http.close()
    }
}

internal fun validToken(token: CharArray): Boolean =
    token.size <= 256 && token.all { it.code in 0x20..0x7E }
