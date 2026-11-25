package app.fieldwatch.sweep.network

import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Reports disclose endpoint identifiers only after explicit operator opt-in. */
object NetworkReports {
    fun text(session: NetworkSession, includeIdentifiers: Boolean = false): String = buildString {
        appendLine("SWEEP network snapshot")
        appendLine("Source: ${if (session.source == NetworkSource.NSD) "Android NSD results" else "Owner-imported router inventory (reported)"}")
        appendLine("State: ${session.state.name.lowercase(java.util.Locale.ROOT)}")
        appendLine("${if (session.source == NetworkSource.ROUTER_IMPORT) "Imported" else "Session started"}: ${timestamp(session.startedAt)}")
        appendLine("Session ended: ${session.endedAt?.let(::timestamp) ?: "unknown / not recorded"}")
        if (session.source == NetworkSource.ROUTER_IMPORT) {
            appendLine("Router export captured: ${session.capturedAt?.let(::timestamp) ?: "unknown"}")
            appendLine("Reported inventory records: ${session.endpoints.size}")
            appendLine("Reported current at export capture: ${session.endpoints.count { it.reportedCurrent == true }}")
            appendLine("Reported not current at export capture: ${session.endpoints.count { it.reportedCurrent == false }}")
            appendLine("Current status unreported: ${session.endpoints.count { it.reportedCurrent == null }}")
            appendLine("Router inventory is reported information at its capture time. Importing it does not test current reachability or observe devices locally.")
        } else {
            appendLine("Retained service endpoint records: ${session.endpoints.size}")
            appendLine("Discovery started: ${session.coverage.discoveryStarted}")
            appendLine("Scoped to selected network: ${session.coverage.scopedToNetwork}")
            appendLine("Service types requested / started / failed: ${session.coverage.serviceTypesRequested.size} / ${session.coverage.typesStarted.size} / ${session.coverage.typesFailed.size}")
            appendLine("Resolved / unresolved results: ${session.coverage.resolvedServices} / ${session.coverage.unresolvedServices}")
            appendLine("Results not attributed or retained: ${session.coverage.droppedServices}")
            appendLine("Coverage error recorded: ${session.coverage.error != null}")
            appendLine("NSD timestamps show when Android results were received. Advertisements and resolutions can be cached; results do not prove a device is currently online. Absence of results does not prove absence of devices.")
        }
        appendLine("Names and addresses are endpoint evidence within this snapshot, not permanent device identity.")
        appendLine("This snapshot contains no traffic recording and does not establish whether an endpoint contains a camera or microphone.")
        if (!includeIdentifiers) {
            appendLine("Endpoint names, addresses, MACs, service names, TXT values and network labels omitted.")
            return@buildString
        }
        appendLine()
        appendLine("Identifiers included by explicit choice:")
        session.networkLabel?.let { appendLine("Owner network label: ${quoted(it)}") }
        if (session.source == NetworkSource.NSD) {
            appendLine("Coverage status: ${quoted(session.coverage.status)}")
            session.coverage.error?.let { appendLine("Coverage error: ${quoted(it)}") }
            appendLine("Types requested: ${session.coverage.serviceTypesRequested.joinToString(", ", transform = ::quoted)}")
            appendLine("Types started: ${session.coverage.typesStarted.joinToString(", ", transform = ::quoted)}")
            appendLine("Types failed: ${session.coverage.typesFailed.joinToString(", ", transform = ::quoted)}")
        }
        session.endpoints.forEachIndexed { index, endpoint ->
            appendLine()
            appendLine("Record ${index + 1} (${if (endpoint.source == NetworkSource.NSD) "NSD result" else "router-reported inventory"}):")
            if (endpoint.source == NetworkSource.NSD) {
                appendLine("Service type: ${quoted(endpoint.serviceType)}")
                appendLine("Service name: ${quoted(endpoint.instanceName)}")
                appendLine("First result received: ${endpoint.firstSeenAt?.let(::timestamp) ?: "unknown"}")
                appendLine("Last result received: ${endpoint.lastSeenAt?.let(::timestamp) ?: "unknown"}")
            } else {
                appendLine("Router-reported current at capture: ${endpoint.reportedCurrent?.toString() ?: "unreported"}")
                appendLine("Router-reported last seen: ${endpoint.reportedLastSeenAt?.let(::timestamp) ?: "unreported"}")
                appendLine("Imported: ${endpoint.importedAt?.let(::timestamp) ?: "unknown"}")
                endpoint.reportedMacAddress?.let { appendLine("Reported MAC: ${quoted(it)}") }
                endpoint.reportedIdentifier?.let { appendLine("Router-reported identifier: ${quoted(it)}") }
            }
            appendLine("${if (endpoint.source == NetworkSource.ROUTER_IMPORT) "Reported hostnames" else "Hostnames from result"}: ${endpoint.hostnames.joinToString(", ", transform = ::quoted).ifEmpty { "unknown" }}")
            appendLine("${if (endpoint.source == NetworkSource.ROUTER_IMPORT) "Reported addresses" else "Addresses from result"}: ${endpoint.addresses.joinToString(", ", transform = ::quoted).ifEmpty { "unknown" }}")
            endpoint.port?.let { appendLine("Service port: $it") }
            endpoint.txt.toSortedMap().forEach { (key, value) -> appendLine("TXT ${quoted(key)}: ${quoted(value)}") }
        }
    }

    private fun quoted(value: String): String = Json.encodeToString(value)
    private fun timestamp(value: Long): String = Instant.ofEpochMilli(value).toString()
}
