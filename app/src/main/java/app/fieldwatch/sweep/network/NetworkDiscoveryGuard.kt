package app.fieldwatch.sweep.network

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** These are advertised protocol types, never physical-device classifications. */
object NetworkDiscoveryTypes {
    val requested: List<String> = listOf(
        "_http._tcp.", "_https._tcp.", "_rtsp._tcp.", "_ipp._tcp.",
        "_airplay._tcp.", "_raop._tcp.", "_googlecast._tcp.", "_hap._tcp.",
    )

    fun canonical(type: String): String? = requested.firstOrNull {
        it.removeSuffix(".") == type.lowercase(Locale.ROOT).removePrefix(".").removeSuffix(".")
    }

    fun validName(name: String): Boolean = name.isNotBlank() && name.length <= 128 &&
        name.toByteArray(StandardCharsets.UTF_8).size <= 256 && name.none {
            it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
        }

    /** Stable only within this receiver session; boundaries cannot collide after normalization. */
    fun key(session: String, type: String, name: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((type + "\u0000" + name).toByteArray(StandardCharsets.UTF_8))
        return session + ":" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

internal data class NetworkDiscoverySource(
    val defaultNetwork: String?,
    val wifi: Boolean,
    val vpn: Boolean,
    val permitted: Boolean,
    val wifiInterface: String?,
)

/** Platform discovery and resolution callbacks must carry the captured network explicitly. */
internal class NetworkDiscoveryGuard {
    private var generation = 0L
    private var selected: String? = null
    private var selectedInterface: String? = null

    fun begin(network: String, wifiInterface: String): Long {
        selected = network
        selectedInterface = wifiInterface
        return ++generation
    }

    fun cancel() { generation++; selected = null; selectedInterface = null }
    fun active(token: Long): Boolean = selected != null && token == generation

    fun acceptsSource(token: Long, source: NetworkDiscoverySource): Boolean = active(token) &&
        source.permitted && source.wifi && !source.vpn && source.defaultNetwork == selected &&
        source.wifiInterface == selectedInterface

    fun acceptsService(token: Long, source: NetworkDiscoverySource, serviceNetwork: String?): Boolean =
        acceptsSource(token, source) && serviceNetwork != null && serviceNetwork == selected

    companion object {
        fun supported(sdk: Int): Boolean = sdk >= 33
    }
}
