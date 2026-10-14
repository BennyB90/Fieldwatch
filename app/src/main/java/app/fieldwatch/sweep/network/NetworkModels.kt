package app.fieldwatch.sweep.network

import kotlinx.serialization.Serializable

@Serializable
enum class NetworkSource { NSD, ROUTER_IMPORT }

@Serializable
enum class NetworkSessionState { ACTIVE, COMPLETE, INTERRUPTED }

/**
 * An endpoint record within one session, not a permanent physical-device identity.
 * NSD timestamps describe receipt of Android results, which may contain cached data.
 * Imported records have no local observation timestamps; reported fields belong to
 * the router export at NetworkSession.capturedAt, not to the time of import.
 */
@Serializable
data class NetworkEndpoint(
    val id: String,
    val serviceType: String,
    val firstSeenAt: Long?,
    val lastSeenAt: Long?,
    val source: NetworkSource,
    val instanceName: String = "",
    val hostnames: List<String> = emptyList(),
    val addresses: List<String> = emptyList(),
    val port: Int? = null,
    val txt: Map<String, String> = emptyMap(),
    val reportedCurrent: Boolean? = null,
    val reportedLastSeenAt: Long? = null,
    val reportedMacAddress: String? = null,
    val reportedIdentifier: String? = null,
    val importedAt: Long? = null,
)

@Serializable
data class NetworkCoverage(
    val discoveryStarted: Boolean = false,
    val serviceTypesRequested: List<String> = emptyList(),
    val resolvedServices: Int = 0,
    val unresolvedServices: Int = 0,
    val scopedToNetwork: Boolean = false,
    val droppedServices: Int = 0,
    val typesStarted: List<String> = emptyList(),
    val typesFailed: List<String> = emptyList(),
    val status: String = "Not started",
    val error: String? = null,
)

@Serializable
data class NetworkSession(
    val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val state: NetworkSessionState = NetworkSessionState.ACTIVE,
    val endpoints: List<NetworkEndpoint> = emptyList(),
    val coverage: NetworkCoverage = NetworkCoverage(),
    val source: NetworkSource = NetworkSource.NSD,
    val networkLabel: String? = null,
    /** Router-export capture time. Import/session start time is a separate value. */
    val capturedAt: Long? = null,
)

data class RouterInventoryImport(
    val capturedAt: Long,
    val networkLabel: String?,
    val endpoints: List<NetworkEndpoint>,
)

/** Fixed limits shared by imports and app-private snapshot storage. */
object NetworkLimits {
    const val MAX_SESSIONS = 20
    const val RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
    const val MAX_ENDPOINTS = 128
    const val MAX_IMPORT_BYTES = 1_048_576
    const val MAX_FILE_BYTES = 16 * 1_048_576
    const val MAX_ADDRESSES = 16
    const val MAX_HOSTNAMES = 8
    const val MAX_SERVICE_TYPES = 32
    const val MAX_TXT_ENTRIES = 16
    const val MAX_TXT_CHARACTERS = 4_096
}

/** Literal syntax validation only. Never resolves a hostname or opens a socket. */
object NetworkAddressValidator {
    fun validate(raw: String): String {
        require(raw.length in 2..80 && !NetworkValidation.hasControls(raw) && raw == raw.trim()) {
            "Inventory addresses must be literal IPv4 or IPv6 addresses."
        }
        val address = raw.substringBefore('%')
        val scope = raw.substringAfter('%', "")
        require(!raw.contains('%') || (address.contains(':') && Regex("[A-Za-z0-9_.-]{1,32}").matches(scope))) {
            "The IPv6 address has an invalid interface scope."
        }
        require(if (address.contains(':')) validIpv6(address) else validIpv4(address)) {
            "Inventory addresses must be literal IPv4 or IPv6 addresses."
        }
        // Interface names are case-sensitive; keep the scope suffix unchanged.
        return address.lowercase(java.util.Locale.ROOT) + if (scope.isEmpty()) "" else "%$scope"
    }

    private fun validIpv4(address: String): Boolean {
        val parts = address.split('.')
        return parts.size == 4 && parts.all { part ->
            part.length in 1..3 && part.all { it in '0'..'9' } &&
                (part.length == 1 || part[0] != '0') && (part.toIntOrNull() ?: 256) <= 255
        }
    }

    private fun validIpv6(address: String): Boolean {
        if (address.isEmpty() || address.any { it !in "0123456789abcdefABCDEF:." }) return false
        val compressed = address.contains("::")
        if (compressed && address.indexOf("::") != address.lastIndexOf("::")) return false
        val sides = if (compressed) address.split("::", limit = 2) else listOf(address)
        var units = 0
        val nonEmptyParts = sides.flatMap { side -> if (side.isEmpty()) emptyList() else side.split(':') }
        if (nonEmptyParts.any { it.isEmpty() }) return false
        nonEmptyParts.forEachIndexed { index, part ->
            if (part.contains('.')) {
                if (index != nonEmptyParts.lastIndex || !validIpv4(part)) return false
                // An IPv4 tail must actually be the final component, not precede ::.
                if (!address.endsWith(part)) return false
                units += 2
            } else {
                if (part.length !in 1..4 || part.any { it !in "0123456789abcdefABCDEF" }) return false
                units++
            }
        }
        return if (compressed) units < 8 else units == 8
    }
}

internal object NetworkValidation {
    private val sessionId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    fun hasControls(value: String): Boolean = value.any {
        it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
    }

    fun text(value: String, label: String, maximum: Int, emptyAllowed: Boolean = true): String {
        require(value.length <= maximum && !hasControls(value)) { "$label contains unsupported or oversized text." }
        require(emptyAllowed || value.isNotBlank()) { "$label cannot be empty." }
        return value
    }

    fun mac(raw: String): String {
        require(Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}|(?:[0-9A-Fa-f]{2}-){5}[0-9A-Fa-f]{2}").matches(raw)) {
            "Inventory MAC addresses must contain six hexadecimal bytes."
        }
        return raw.replace('-', ':').uppercase(java.util.Locale.ROOT)
    }

    fun session(value: NetworkSession): NetworkSession {
        require(sessionId.matches(value.id)) { "A network session needs a valid ID." }
        require(value.startedAt > 0 && (value.endedAt == null || value.endedAt >= value.startedAt)) { "A network session has invalid timestamps." }
        require(value.state != NetworkSessionState.ACTIVE || value.endedAt == null) { "An active network session cannot have an end time." }
        require(value.endpoints.size <= NetworkLimits.MAX_ENDPOINTS) { "A network session can retain up to 128 endpoint records." }
        require(value.endpoints.map { it.id }.toSet().size == value.endpoints.size) { "Network endpoint IDs must be distinct within a session." }
        value.networkLabel?.let { text(it, "Network label", 120, false) }
        if (value.source == NetworkSource.ROUTER_IMPORT) {
            require(value.capturedAt != null && value.capturedAt > 0 && value.capturedAt <= value.startedAt) { "A router inventory needs its original capture timestamp." }
        } else {
            require(value.capturedAt == null) { "NSD sessions cannot claim a router capture timestamp." }
        }
        value.endpoints.forEach { record -> endpoint(record, value) }
        coverage(value.coverage)
        return value
    }

    private fun endpoint(value: NetworkEndpoint, session: NetworkSession) {
        text(value.id, "Endpoint ID", 512, false)
        require(value.source == session.source) { "Keep NSD results and router inventory in separate sessions." }
        text(value.serviceType, "Service type", 255)
        text(value.instanceName, "Service name", 255)
        require(value.hostnames.size <= NetworkLimits.MAX_HOSTNAMES && value.addresses.size <= NetworkLimits.MAX_ADDRESSES) { "An endpoint has too many names or addresses." }
        value.hostnames.forEach { text(it, "Hostname", 255, false) }
        value.addresses.forEach(NetworkAddressValidator::validate)
        require(value.port == null || value.port in 1..65_535) { "An endpoint has an invalid port." }
        require(value.txt.size <= NetworkLimits.MAX_TXT_ENTRIES && value.txt.entries.sumOf { it.key.length + it.value.length } <= NetworkLimits.MAX_TXT_CHARACTERS) { "An endpoint has too much TXT data." }
        value.txt.forEach { (key, content) -> text(key, "TXT key", 255, false); text(content, "TXT value", 1_024) }
        if (value.source == NetworkSource.NSD) {
            require(value.firstSeenAt != null && value.lastSeenAt != null && value.firstSeenAt >= session.startedAt && value.lastSeenAt >= value.firstSeenAt) { "NSD result receipt timestamps are invalid." }
            require(session.endedAt == null || value.lastSeenAt <= session.endedAt) { "NSD results cannot be received after the session ended." }
            require(value.reportedCurrent == null && value.reportedLastSeenAt == null && value.reportedMacAddress == null && value.reportedIdentifier == null && value.importedAt == null) { "NSD results cannot claim router-reported device state." }
        } else {
            val capturedAt = session.capturedAt ?: throw IllegalArgumentException("A router inventory needs its original capture timestamp.")
            require(value.firstSeenAt == null && value.lastSeenAt == null) { "Router inventory cannot claim locally observed timestamps." }
            require(value.importedAt != null && value.importedAt >= capturedAt && value.importedAt == session.startedAt) { "Router inventory import timestamps are invalid." }
            require(value.reportedLastSeenAt == null || (value.reportedLastSeenAt > 0 && value.reportedLastSeenAt <= capturedAt)) { "Router-reported last-seen timestamps are invalid." }
            value.reportedMacAddress?.let(::mac)
            value.reportedIdentifier?.let { text(it, "Reported identifier", 128, false) }
        }
    }

    private fun coverage(value: NetworkCoverage) {
        require(value.resolvedServices >= 0 && value.unresolvedServices >= 0 && value.droppedServices >= 0) { "Network coverage counts cannot be negative." }
        listOf(value.serviceTypesRequested, value.typesStarted, value.typesFailed).forEach { types ->
            require(types.size <= NetworkLimits.MAX_SERVICE_TYPES && types.toSet().size == types.size) { "Network coverage has too many or duplicate service types." }
            types.forEach { text(it, "Service type", 255, false) }
        }
        text(value.status, "Coverage status", 512)
        value.error?.let { text(it, "Coverage error", 512) }
    }
}
