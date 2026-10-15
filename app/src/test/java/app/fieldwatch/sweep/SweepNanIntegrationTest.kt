package app.fieldwatch.sweep

import app.fieldwatch.data.DeviceStore
import app.fieldwatch.domain.*
import org.junit.Assert.*
import org.junit.Test

class SweepNanIntegrationTest {
    @Test fun nanReportsDecodedPinsWithoutInventingStrengthOrPermanentAddress() {
        val radio = Observation(kind = RadioKind.WIFI, mac = "NAN:session:1:1", name = "Remote ID (Wi-Fi NAN)",
            rssi = 127, channel = 0, frequencyMhz = 0, hiddenSsid = false, serviceUuids = emptyList(),
            manufacturerId = null, manufacturerDataHex = "", rawHex = "", extras = "", at = 1_000,
            facts = RadioFacts(serviceData = listOf(ServiceDataRecord(OpenDroneId.NAN_SERVICE_NAME, "0D" + OpenDroneIdReferenceFixtures.PACK_A))))
        val finding = sweepFindingForObservation(DeviceStore(), radio, emptyList(), null)
        assertEquals(SweepCategory.DRONE, finding.category)
        assertEquals("WIFI NAN", finding.transport)
        assertTrue(finding.signalSamples.isEmpty())
        assertEquals(2, finding.dronePins.size)
        assertTrue(finding.dronePins.all { it.observedAt == 1_000L })
        assertFalse(finding.evidence.any { "127 dBm" in it.value })
        val session = SweepSession(id = "nan", startedAt = 500, endedAt = 2_000, state = SweepSessionState.COMPLETE,
            findings = listOf(finding), coverage = SweepCoverage(wifiNanSupported = true, wifiNanSamples = 1, wifiNanStatus = "PRIVATE: SSID, 40.0,-74.0"))
        val report = SweepReports.text(session)
        assertTrue(report.contains("WIFI NAN"))
        assertTrue(report.contains("signal unavailable"))
        assertFalse(report.contains("PRIVATE"))
        assertFalse(report.contains("40.0"))
        assertFalse(report.contains("NAN:session"))
    }
}
