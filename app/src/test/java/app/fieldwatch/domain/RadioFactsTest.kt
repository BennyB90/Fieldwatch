package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioFactsTest {
    @Test
    fun connectableYesSurvivesScanResponse() {
        val adv = RadioFacts(connectable = true)
        val scanRsp = RadioFacts(connectable = false)
        assertEquals(true, adv.merge(scanRsp).connectable)
        assertEquals(true, scanRsp.merge(adv).connectable)
    }

    @Test
    fun connectableStaysNoUntilAConnectableAd() {
        val first = RadioFacts(connectable = false)
        val again = RadioFacts(connectable = false)
        assertEquals(false, first.merge(again).connectable)
    }

    @Test
    fun connectableNullDoesNotClear() {
        val known = RadioFacts(connectable = false)
        assertEquals(false, known.merge(RadioFacts()).connectable)
        assertNull(RadioFacts().merge(RadioFacts()).connectable)
    }

    @Test
    fun eddystoneFramesAccumulateInsteadOfReplacing() {
        val uid = RadioFacts(
            serviceData = listOf(ServiceDataRecord("FEAA", "00AABBCCDDEEFF00112233445566778899")),
        )
        val url = RadioFacts(
            serviceData = listOf(ServiceDataRecord("FEAA", "1001676F6F676C6507")),
        )
        val tlm = RadioFacts(
            serviceData = listOf(ServiceDataRecord("0000FEAA-0000-1000-8000-00805F9B34FB", "2000ABCD")),
        )
        val merged = uid.merge(url).merge(tlm)
        assertEquals(3, merged.serviceData.size)
        assertEquals(setOf("00", "10", "20"), merged.serviceData.map { it.dataHex.take(2) }.toSet())
    }

    @Test
    fun otherServiceDataStillReplacesByUuid() {
        val first = RadioFacts(serviceData = listOf(ServiceDataRecord("FE2C", "AA")))
        val second = RadioFacts(serviceData = listOf(ServiceDataRecord("FE2C", "BBCC")))
        val merged = first.merge(second)
        assertEquals(1, merged.serviceData.size)
        assertEquals("BBCC", merged.serviceData.single().dataHex)
    }

    @Test
    fun remoteIdShortLocationReplacesLongPackAcrossUuidAliases() {
        val first = RadioFacts(serviceData = listOf(
            ServiceDataRecord("0000FFFA-0000-1000-8000-00805F9B34FB", "0D00" + OpenDroneIdReferenceFixtures.PACK_A),
        ))
        val next = RadioFacts(serviceData = listOf(
            ServiceDataRecord("FFFA", "0D01" + OpenDroneIdReferenceFixtures.LOCATION_B),
        ))
        val merged = first.merge(next)
        assertEquals(1, merged.serviceData.size)
        assertEquals(next.serviceData.single(), merged.serviceData.single())
        assertEquals(40.001, OpenDroneId.fromFacts(merged).lat!!, 1e-7)
    }

    @Test
    fun nanShortLocationReplacesLongPackInsteadOfFreezingTheFirstPin() {
        val initial = RadioFacts(serviceData = listOf(
            ServiceDataRecord(OpenDroneId.NAN_SERVICE_NAME, "00" + OpenDroneIdReferenceFixtures.PACK_A),
        ))
        val updated = RadioFacts(serviceData = listOf(
            ServiceDataRecord(OpenDroneId.NAN_SERVICE_NAME, "01" + OpenDroneIdReferenceFixtures.LOCATION_B),
        ))
        val merged = initial.merge(updated)
        assertEquals(updated.serviceData, merged.serviceData)
        assertEquals(40.001, OpenDroneId.fromFacts(merged).lat!!, 1e-7)
        assertEquals(-74.002, OpenDroneId.fromFacts(merged).lon!!, 1e-7)
        val newSystem = RadioFacts(serviceData = listOf(
            ServiceDataRecord(OpenDroneId.NAN_SERVICE_NAME, "02" + OpenDroneIdReferenceFixtures.SYSTEM_B),
        ))
        assertEquals(OperatorLoc.LIVE, OpenDroneId.fromFacts(merged.merge(newSystem)).opKind)
        assertEquals(39.901, OpenDroneId.fromFacts(merged.merge(newSystem)).opLat!!, 1e-7)
    }

    @Test
    fun otherServiceDataStillKeepsLongerMetadataWhenShortDataArrives() {
        val first = RadioFacts(serviceData = listOf(ServiceDataRecord("FE2C", "AABBCC")))
        val shorter = RadioFacts(serviceData = listOf(ServiceDataRecord("FE2C", "DD")))
        assertEquals(first.serviceData, first.merge(shorter).serviceData)
    }

    @Test
    fun wifiRemoteIdKeepsUpdatingAfterVendorMetadataCapacity() {
        var facts = RadioFacts(vendorIes = (0 until 12).map {
            VendorIeRecord("00:11:22", it, "AABBCC")
        })
        for (counter in 0..20) {
            val body = if (counter == 20) OpenDroneIdReferenceFixtures.LOCATION_B else OpenDroneIdReferenceFixtures.LOCATION_A
            facts = facts.merge(RadioFacts(vendorIes = listOf(
                VendorIeRecord("fa:0b:bc", 0x0D, "%02X".format(counter) + body),
            )))
        }
        assertEquals(12, facts.vendorIes.size)
        assertEquals(1, facts.vendorIes.count { it.oui.equals("FA:0B:BC", ignoreCase = true) })
        assertEquals(40.001, OpenDroneId.fromFacts(facts).lat!!, 1e-7)
        assertEquals(-74.002, OpenDroneId.fromFacts(facts).lon!!, 1e-7)
    }
}
