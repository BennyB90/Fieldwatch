package app.fieldwatch.sweep.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkReportsTest {
    private val now = 1_700_000_000_000L

    @Test
    fun defaultReportDoesNotLeakNamesIpsTxtNetworkLabelsOrCoverageErrors() {
        val session = NetworkSession(
            id = "private-session-id",
            startedAt = now,
            networkLabel = "private-network-label",
            endpoints = listOf(NetworkEndpoint("private-record-id", "_private-type._tcp.", now, now + 1_000, NetworkSource.NSD,
                instanceName = "private-service-name", hostnames = listOf("private-host.local"), addresses = listOf("192.168.13.37"),
                port = 8123, txt = mapOf("private-txt-key" to "private-txt-value"))),
            coverage = NetworkCoverage(true, listOf("_private-type._tcp."), 1, 0, true, 2,
                listOf("_private-type._tcp."), emptyList(), "private-coverage-status", "private-coverage-error"),
        )
        val report = NetworkReports.text(session)
        listOf("private-", "192.168.13.37", "8123").forEach { assertFalse(report.contains(it)) }
        assertTrue(report.contains("Retained service endpoint records: 1"))
        assertTrue(report.contains("Results not attributed or retained: 2"))
        assertFalse(report.contains("dropped at collection limit"))
        assertTrue(report.contains("can be cached"))
        assertTrue(report.contains("does not prove absence"))
        val detailed = NetworkReports.text(session, includeIdentifiers = true)
        listOf("private-network-label", "private-service-name", "private-host.local", "192.168.13.37", "private-txt-value", "private-coverage-error").forEach { assertTrue(detailed.contains(it)) }
        assertFalse(detailed.contains("private-session-id"))
        assertFalse(detailed.contains("private-record-id"))
        assertTrue(detailed.contains("First result received:"))
        assertTrue(detailed.contains("Last result received:"))
    }

    @Test
    fun importedReportKeepsCaptureImportAndReportedOfflineStatusDistinct() {
        val session = NetworkSession("inventory-1", now, now, NetworkSessionState.COMPLETE,
            listOf(NetworkEndpoint("one", "", null, null, NetworkSource.ROUTER_IMPORT,
                hostnames = listOf("private-host.local"), reportedCurrent = false, reportedLastSeenAt = now - 86_400_000,
                reportedMacAddress = "AA:BB:CC:DD:EE:FF", reportedIdentifier = "private-lease-id", importedAt = now)),
            source = NetworkSource.ROUTER_IMPORT, capturedAt = now - 60_000)
        val report = NetworkReports.text(session)
        assertTrue(report.contains("Reported not current at export capture: 1"))
        assertTrue(report.contains("Importing it does not test current reachability"))
        assertFalse(report.contains("private-host"))
        assertFalse(report.contains("AA:BB"))
        assertFalse(report.contains("private-lease"))
        val detailed = NetworkReports.text(session, true)
        assertTrue(detailed.contains("Router export captured: 2023-11-14T22:12:20Z"))
        assertTrue(detailed.contains("Imported: 2023-11-14T22:13:20Z"))
        assertTrue(detailed.contains("Router-reported current at capture: false"))
        assertTrue(detailed.contains("Router-reported last seen:"))
        assertFalse(detailed.contains("First result received:"))
    }

    @Test
    fun processDeathReportDoesNotInventAnEndTimeAndUntrustedTextCannotInjectLines() {
        val report = NetworkReports.text(NetworkSession("one", now, state = NetworkSessionState.INTERRUPTED,
            endpoints = listOf(NetworkEndpoint("one", "_http._tcp.", now, now, NetworkSource.NSD,
                instanceName = "Name\nState: complete"))), true)
        assertTrue(report.contains("State: interrupted"))
        assertTrue(report.contains("Session ended: unknown / not recorded"))
        assertFalse(report.contains("Name\nState: complete"))
        assertTrue(report.contains("Name\\nState: complete"))
    }
}
