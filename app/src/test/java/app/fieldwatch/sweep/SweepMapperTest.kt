package app.fieldwatch.sweep

import app.fieldwatch.domain.DefaultCatalog
import app.fieldwatch.domain.Fleet
import app.fieldwatch.domain.MatchRule
import app.fieldwatch.domain.OpenDroneIdReferenceFixtures
import app.fieldwatch.domain.RadioFacts
import app.fieldwatch.domain.RadioKind
import app.fieldwatch.domain.RuleKind
import app.fieldwatch.domain.ServiceDataRecord
import app.fieldwatch.domain.SignatureClass
import app.fieldwatch.domain.Sighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepMapperTest {
    @Test
    fun onlyFinderAndCameraClassesGetConsumerCategories() {
        val radio = radio("Something")
        mapOf(
            SignatureClass.FINDER to SweepCategory.TRACKER,
            SignatureClass.CAMERA to SweepCategory.CAMERA,
            SignatureClass.DRONE to SweepCategory.DRONE,
            SignatureClass.BEACON to SweepCategory.OTHER,
            SignatureClass.SURVEILLANCE to SweepCategory.OTHER,
            SignatureClass.AUDIO to SweepCategory.OTHER,
            SignatureClass.PHONE to SweepCategory.OTHER,
        ).forEach { (upstream, consumer) ->
            val signature = fleet(kind = upstream, rules = listOf(MatchRule(RuleKind.NAME_CONTAINS, "Something")))
            assertEquals(upstream.name, consumer, SweepMapper.finding(radio, listOf(signature)).category)
        }
    }

    @Test
    fun evidenceContainsOnlyTheRuleThatActuallyMatched() {
        val signature = fleet(rules = listOf(
            MatchRule(RuleKind.NAME_CONTAINS, "camera"),
            MatchRule(RuleKind.SERVICE_UUID, "BEEF"),
            MatchRule(RuleKind.MANUFACTURER_ID, companyId = 0xBEEF),
            MatchRule(RuleKind.NAME_GLOB, "never*", enabled = false),
        ))
        val finding = SweepMapper.finding(radio("My camera"), listOf(signature))
        assertEquals(listOf("Test camera"), finding.signatureNames)
        assertTrue(finding.evidence.any { it.label == "Advertised name rule" })
        assertFalse(finding.evidence.any { it.label == "Bluetooth service rule" || it.label == "Manufacturer rule" })
        assertFalse(finding.evidence.any { "BEEF" in it.value || "never" in it.value })
    }

    @Test
    fun staleDirectFleetIdCannotManufactureACameraMatch() {
        val signature = fleet(rules = listOf(MatchRule(RuleKind.NAME_CONTAINS, "camera")))
        val finding = SweepMapper.finding(radio("office speaker").copy(fleetIds = setOf(signature.id)), listOf(signature))
        assertEquals(SweepCategory.OTHER, finding.category)
        assertTrue(finding.signatureNames.isEmpty())
    }

    @Test
    fun disabledFleetDoesNotBecomeAMatch() {
        val signature = fleet(rules = listOf(MatchRule(RuleKind.NAME_CONTAINS, "camera"))).copy(enabled = false)
        assertEquals(SweepCategory.OTHER, SweepMapper.finding(radio("camera"), listOf(signature)).category)
    }

    @Test
    fun clusterMatchKeepsPeerSemanticsWithoutClaimingUnmatchedNameRule() {
        val signature = fleet(rules = listOf(MatchRule(RuleKind.NAME_CONTAINS, "camera"))).copy(minPeers = 3, clusterByOui = true)
        val finding = SweepMapper.finding(radio("").copy(fleetIds = setOf(signature.id)), listOf(signature))
        assertEquals(SweepCategory.CAMERA, finding.category)
        assertTrue(finding.evidence.any { it.label == "Cluster signature" })
        assertFalse(finding.evidence.any { it.label == "Advertised name rule" })
    }

    @Test
    fun catalogSuppressesAirTagWhenAppleContinuityLabelsTheSameRadio() {
        val device = radio("iPhone").copy(
            manufacturerId = 0x004C,
            manufacturerDataHex = "1200",
            facts = RadioFacts(mfgRecords = listOf(
                app.fieldwatch.domain.MfgRecord(0x004C, "1200"),
                app.fieldwatch.domain.MfgRecord(0x004C, "100700000000000000"),
            )),
        )
        val finding = SweepMapper.finding(device, DefaultCatalog.fleets())
        assertFalse(finding.signatureNames.contains("Apple AirTags"))
        assertEquals(SweepCategory.OTHER, finding.category)
    }

    @Test
    fun actualOpenDroneIdDecodedWithoutACatalogRowGetsAircraftAndTakeoffPins() {
        val device = radio("").copy(facts = RadioFacts(serviceData = listOf(
            ServiceDataRecord("FFFA", "0D0D" + OpenDroneIdReferenceFixtures.PACK_A),
        )))
        val finding = SweepMapper.finding(device, emptyList())
        assertEquals(SweepCategory.DRONE, finding.category)
        assertEquals("1581F3YTDJ1D0031Z530", finding.droneId)
        assertEquals(listOf(SweepPinKind.AIRCRAFT, SweepPinKind.TAKEOFF), finding.dronePins.map { it.kind })
        assertEquals(40.0, finding.dronePins[0].latitude, 1e-7)
        assertEquals(-73.9, finding.dronePins[1].longitude, 1e-7)
        assertTrue(finding.dronePins.all { it.observedAt == null })
    }

    @Test
    fun remoteUuidAloneAndCustomPayloadCoordinatesDoNotCreateADrone() {
        val device = radio("").copy(serviceUuids = listOf("FFFA"), payloadLat = 40.0, payloadLon = -74.0, payloadUasId = "custom-field")
        val finding = SweepMapper.finding(device, emptyList())
        assertEquals(SweepCategory.OTHER, finding.category)
        assertTrue(finding.dronePins.isEmpty())
        assertNull(finding.droneId)
    }

    @Test
    fun stickyOperatorKindsRemainDistinctAndDoNotBorrowRadioTimestamp() {
        val signature = fleet(kind = SignatureClass.DRONE, rules = listOf(MatchRule(RuleKind.NAME_CONTAINS, "drone")))
        val device = radio("drone").copy(payloadOpLat = 34.1, payloadOpLon = -118.2)
        mapOf(
            0 to SweepPinKind.TAKEOFF,
            1 to SweepPinKind.CONTROL_STATION,
            2 to SweepPinKind.FIXED_OPERATOR,
            3 to SweepPinKind.UNSPECIFIED_OPERATOR,
        ).forEach { (kind, expected) ->
            val finding = SweepMapper.finding(device.copy(payloadOpKind = kind), listOf(signature))
            assertEquals(expected, finding.dronePins.single().kind)
            assertNull(finding.dronePins.single().observedAt)
        }
        assertEquals(SweepPinKind.UNSPECIFIED_OPERATOR, SweepMapper.finding(device, listOf(signature)).dronePins.single().kind)
    }

    @Test
    fun invalidCoordinatesCannotAppearAsMapPins() {
        val signature = fleet(kind = SignatureClass.DRONE, rules = listOf(MatchRule(RuleKind.NAME_CONTAINS, "drone")))
        listOf(0.0 to 0.0, Double.NaN to -74.0, 91.0 to 1.0, 1.0 to -181.0).forEach { (lat, lon) ->
            val finding = SweepMapper.finding(radio("drone").copy(payloadLat = lat, payloadLon = lon, payloadOpLat = lat, payloadOpLon = lon), listOf(signature))
            assertTrue(finding.dronePins.isEmpty())
        }
    }

    @Test
    fun rssiIsPresentedAsSignalStrengthAndNotConvertedToDistance() {
        val finding = SweepMapper.finding(radio("unknown").copy(rssi = -83), emptyList())
        assertEquals(-83, finding.rssi)
        assertTrue(finding.evidence.any { it.label == "Signal strength" && it.value.contains("not a distance measurement") })
    }

    private fun fleet(kind: SignatureClass = SignatureClass.CAMERA, rules: List<MatchRule>) =
        Fleet(id = "test", name = "Test camera", kind = kind, rules = rules)

    private fun radio(name: String) = Sighting(
        key = "BLE|CA:FE:00:00:00:01", kind = RadioKind.BLE, mac = "CA:FE:00:00:00:01", name = name,
        rssi = -61, rssiMin = -70, rssiMax = -50, channel = 0, frequencyMhz = 0, vendor = null,
        randomized = true, hiddenSsid = false, serviceUuids = emptyList(), manufacturerId = null,
        manufacturerDataHex = "", rawHex = "", extras = "", firstSeen = 100L, lastSeen = 900L,
        hitCount = 3, fleetIds = emptySet(), rssiHistory = emptyList(), presence = emptyList(),
    )
}
