package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenDroneIdTest {
    @Test
    fun malformedPacksCannotProducePartialRemoteIdEvidence() {
        val pack = OpenDroneIdReferenceFixtures.PACK_A
        val invalid = listOf(
            pack.replaceRange(2, 4, "01"), // Declared message size must be 25.
            pack.dropLast(2), // Entire declared pack must be present.
            pack.replaceRange(4, 6, "00"),
            pack.replaceRange(4, 6, "0A"),
            pack + "00", // No ignored trailing byte within a framed payload.
            pack.replaceRange(6, 8, "F2"), // A pack cannot contain another pack.
            "F21902" + OpenDroneIdReferenceFixtures.LOCATION_A.repeat(2),
            OpenDroneIdReferenceFixtures.LOCATION_A + "AB",
        )
        invalid.forEach { payload ->
            val ble = RadioFacts(serviceData = listOf(ServiceDataRecord("FFFA", "0D00$payload")))
            val wifi = RadioFacts(vendorIes = listOf(VendorIeRecord("FA:0B:BC", 0x0D, "00$payload")))
            assertEquals(payload, PayloadLocation(), OpenDroneId.fromFacts(ble))
            assertEquals(payload, PayloadLocation(), OpenDroneId.fromFacts(wifi))
            assertTrue(OpenDroneId.wifiFffaPayloads(wifi).isEmpty())
        }
    }

    @Test
    fun maximumReferencePackAndCompatibleHeaderVersionDecode() {
        val payload = OpenDroneIdReferenceFixtures.PACK_MAX
        assertEquals(3 + 9 * 25, payload.length / 2)
        listOf(payload, payload.replaceRange(0, 2, "F3")).forEach { pack ->
            val loc = OpenDroneId.fromFacts(RadioFacts(serviceData = listOf(ServiceDataRecord("FFFA", "0D00$pack"))))
            assertEquals(40.0, loc.lat!!, 1e-7)
            assertEquals(39.9, loc.opLat!!, 1e-7)
            assertEquals("1581F3YTDJ1D0031Z530", loc.uasId)
        }
    }

    @Test
    fun onlyTheBluetoothUuidNamespaceCanSelectRemoteIdDecoding() {
        val payload = "0D00" + OpenDroneIdReferenceFixtures.LOCATION_A
        fun decode(uuid: String) = OpenDroneId.fromFacts(RadioFacts(serviceData = listOf(ServiceDataRecord(uuid, payload))))
        listOf("FFFA", "0000FFFA", "0000FFFA-0000-1000-8000-00805F9B34FB").forEach {
            assertEquals(40.0, decode(it).lat!!, 1e-7)
        }
        listOf("FFFA1234-1234-5678-1234-567812345678", "0000FFFA-1234-5678-1234-567812345678").forEach {
            assertEquals(PayloadLocation(), decode(it))
        }
    }

    @Test
    fun bleLocationHeadingSpeedAndEw() {
        val loc = OpenDroneId.fromFacts(
            RadioFacts(serviceData = listOf(ServiceDataRecord("FFFA", bleWrap(locationMsg(dir = 90, ew = false, speed = 40))))),
        )
        assertEquals(40.0, loc.lat!!, 1e-6)
        assertEquals(-74.0, loc.lon!!, 1e-6)
        assertEquals(90.0, loc.headingDeg!!, 1e-6)
        assertEquals(10.0, loc.speedMps!!, 1e-6)
        assertEquals(2.0, loc.vspeedMps!!, 1e-6)
        val west = OpenDroneId.fromFacts(
            RadioFacts(serviceData = listOf(ServiceDataRecord("FFFA", bleWrap(locationMsg(dir = 90, ew = true, speed = 40))))),
        )
        assertEquals(270.0, west.headingDeg!!, 1e-6)
    }

    @Test
    fun wifiVendorIeLocation() {
        val ie = VendorIeRecord("FA:0B:BC", 0x0D, "00" + locationMsg(dir = 45, ew = false, speed = 8).toHexUpper())
        val loc = OpenDroneId.fromFacts(RadioFacts(vendorIes = listOf(ie)))
        assertEquals(40.0, loc.lat!!, 1e-6)
        assertEquals(45.0, loc.headingDeg!!, 1e-6)
        assertEquals(2.0, loc.speedMps!!, 1e-6)
    }

    @Test
    fun unknownDirectionIsNull() {
        val loc = OpenDroneId.parseMessages(listOf(locationMsg(dir = 255, ew = false, speed = 40)))
        assertNull(loc.headingDeg)
    }

    @Test
    fun wifiMessagePackDecodesIdLocationAndOperator() {
        // DJI RID-1581F3… FA:0B:BC type 13 pack from A54 28 Sep 2026.
        val hex = "D9F2190302123135383146335954444A3144303033315A353330000000" +
            "1220820A00864228110CFF80CF0000F508B2083A022E310A00" +
            "420176E42711B5FF81CF010000000000000005088B02900E00"
        val loc = OpenDroneId.fromFacts(
            RadioFacts(vendorIes = listOf(VendorIeRecord("FA:0B:BC", 0x0D, hex))),
        )
        assertEquals("1581F3YTDJ1D0031Z530", loc.uasId)
        assertEquals(28.7851142, loc.lat!!, 1e-6)
        assertEquals(-81.3629684, loc.lon!!, 1e-6)
        assertEquals(130.0, loc.headingDeg!!, 1e-6)
        assertEquals(2.5, loc.speedMps!!, 1e-6)
        assertEquals(28.7827062, loc.opLat!!, 1e-6)
        assertEquals(-81.3563979, loc.opLon!!, 1e-6)
    }

    @Test
    fun referenceWifiPackDecodesForEveryCounterIncludingThirteen() {
        for (counter in 0..255) {
            val payload = "%02X".format(counter) + OpenDroneIdReferenceFixtures.PACK_A
            val facts = RadioFacts(vendorIes = listOf(VendorIeRecord("FA:0B:BC", 0x0D, payload)))
            val loc = OpenDroneId.fromFacts(facts)
            assertEquals("counter $counter", "1581F3YTDJ1D0031Z530", loc.uasId)
            assertEquals(40.0, loc.lat!!, 1e-7)
            assertEquals(-74.0, loc.lon!!, 1e-7)
            assertEquals(39.9, loc.opLat!!, 1e-7)
            assertEquals(-73.9, loc.opLon!!, 1e-7)
            assertEquals(OperatorLoc.TAKEOFF, loc.opKind)
        }
    }

    @Test
    fun referenceWifiCounterThirteenWrapsAsBleWithoutDroppingByte() {
        val payload = "0D" + OpenDroneIdReferenceFixtures.PACK_A
        assertEquals(
            listOf(
                "0D0D" + OpenDroneIdReferenceFixtures.BASIC,
                "0D0D" + OpenDroneIdReferenceFixtures.LOCATION_A,
                "0D0D" + OpenDroneIdReferenceFixtures.SYSTEM_A,
            ),
            OpenDroneId.wrapWifiAsFffa(payload),
        )
    }

    @Test
    fun bleServiceDataRequiresApplicationCodeAndHasItsOwnCounter() {
        val good = RadioFacts(serviceData = listOf(
            ServiceDataRecord("FFFA", "0D0D" + OpenDroneIdReferenceFixtures.PACK_A),
        ))
        assertEquals(40.0, OpenDroneId.fromFacts(good).lat!!, 1e-7)
        val wrongCode = good.copy(serviceData = listOf(
            ServiceDataRecord("FFFA", "000D" + OpenDroneIdReferenceFixtures.PACK_A),
        ))
        assertNull(OpenDroneId.fromFacts(wrongCode).pin())
        val unframed = good.copy(serviceData = listOf(
            ServiceDataRecord("FFFA", OpenDroneIdReferenceFixtures.LOCATION_A),
        ))
        assertNull(OpenDroneId.fromFacts(unframed).pin())
    }

    private fun bleWrap(msg: ByteArray) = ("0D00" + msg.toHexUpper())

    private fun locationMsg(dir: Int, ew: Boolean, speed: Int): ByteArray {
        val flags = 0x20 or (if (ew) 0x02 else 0)
        val lat = le32(400_000_000)
        val lon = le32(-740_000_000)
        val geo = le16(2200)
        val height = le16(2100)
        return byteArrayOf(
            0x12, flags.toByte(), dir.toByte(), speed.toByte(), 4,
        ) + lat + lon + le16(0) + geo + height + ByteArray(6)
    }

    private fun le32(n: Int) = byteArrayOf(
        (n and 0xFF).toByte(),
        ((n shr 8) and 0xFF).toByte(),
        ((n shr 16) and 0xFF).toByte(),
        ((n shr 24) and 0xFF).toByte(),
    )

    private fun le16(n: Int) = byteArrayOf((n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte())
}
