package app.fieldwatch.sweep

import app.fieldwatch.domain.DefaultCatalog
import app.fieldwatch.domain.RadioFacts
import app.fieldwatch.domain.RadioKind
import app.fieldwatch.domain.ServiceDataRecord
import app.fieldwatch.domain.Sighting

/** Local synthetic examples. Calling this performs no scan and writes no session. */
object SweepDemo {
    fun session(now: Long): SweepSession {
        val started = now - 120_000L
        val fleets = DefaultCatalog.fleets()
        // These protocol bytes are sample Remote ID data, not a detection receipt.
        // Location is 40 N, 74 W; System type 0 is a takeoff point, not a live pilot.
        val remoteIdPack = "0D00F21903" +
            "02123135383146335954444A3144303033315A353330000000" +
            "12205A28040084D717007FE4D3000098080000000000000000" +
            "4200C041C81740C1F3D3010000000000000034080000000000"
        val radios = listOf(
            sample("tracker", RadioKind.BLE, "02:00:00:00:00:01", "SAMPLE — Tile tracker", -56, started, now, services = listOf("FEED")),
            sample("camera", RadioKind.BLE, "02:00:00:00:00:02", "SAMPLE — GoPro camera", -64, started + 8_000L, now, services = listOf("FEA6")),
            sample("drone", RadioKind.BLE, "02:00:00:00:00:03", "SAMPLE — Remote ID drone", -75, started + 19_000L, now,
                services = listOf("FFFA"), facts = RadioFacts(serviceData = listOf(ServiceDataRecord("FFFA", remoteIdPack)))),
            sample("other", RadioKind.BLE, "02:00:00:00:00:04", "SAMPLE — Sonos speaker", -69, started + 31_000L, now),
        )
        return SweepSession(
            id = "synthetic-demo-$now",
            startedAt = started,
            endedAt = now,
            state = SweepSessionState.COMPLETE,
            findings = radios.map { radio ->
                SweepMapper.finding(radio, fleets).let { finding ->
                    finding.copy(evidence = listOf(SweepEvidence("Synthetic sample", "Demo data only; no physical device was detected.")) + finding.evidence,
                        signalSamples = listOf(SweepSignalSample(radio.firstSeen, radio.rssi - 8), SweepSignalSample((radio.firstSeen + now) / 2, radio.rssi - 3), SweepSignalSample(now, radio.rssi)))
                }
            },
            coverage = SweepCoverage(
                wifiSupported = true, bleSupported = true, wifiReady = true, bleReady = true,
                wifiStatus = "Synthetic sample — no Wi-Fi scan performed",
                bleStatus = "Synthetic sample — no Bluetooth scan performed",
                wifiSamples = 30, bleSamples = 92,
                wifiLastSampleAt = now, wifiIeAvailable = true, bleExtendedSupported = true,
            ),
            phone = "Synthetic sample phone",
            androidVersion = "14",
            startBattery = 80,
            endBattery = 79,
            demo = true,
        )
    }

    private fun sample(
        id: String,
        kind: RadioKind,
        mac: String,
        name: String,
        rssi: Int,
        first: Long,
        last: Long,
        services: List<String> = emptyList(),
        facts: RadioFacts = RadioFacts.Empty,
    ) = Sighting(
        key = "synthetic-$id", kind = kind, mac = mac, name = name,
        rssi = rssi, rssiMin = rssi - 8, rssiMax = rssi + 4,
        channel = 0, frequencyMhz = 0, vendor = null, randomized = true, hiddenSsid = false,
        serviceUuids = services, manufacturerId = null, manufacturerDataHex = "", rawHex = "", extras = "",
        firstSeen = first, lastSeen = last, hitCount = 12, fleetIds = emptySet(),
        rssiHistory = emptyList(), presence = emptyList(), facts = facts,
    )
}
