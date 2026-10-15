package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenDroneIdNanTest {
    // Content from the independently pinned C encoder; only the NAN counter is added here.
    private fun reference(counter: Int = 13) = bytes("%02X".format(counter) + OpenDroneIdReferenceFixtures.PACK_A)

    @Test fun referenceNanPackDecodesForEveryCounter() {
        for (counter in 0..255) {
            val data = reference(counter)
            assertEquals(3, OpenDroneIdNan.messages(data).size)
            val facts = RadioFacts(serviceData = listOf(ServiceDataRecord(OpenDroneId.NAN_SERVICE_NAME, data.toHexUpper())))
            val decoded = OpenDroneId.fromFacts(facts)
            assertEquals("1581F3YTDJ1D0031Z530", decoded.uasId)
            assertEquals(40.0, decoded.lat!!, 1e-7)
            assertEquals(-74.0, decoded.lon!!, 1e-7)
            assertEquals(39.9, decoded.opLat!!, 1e-7)
            assertEquals(OperatorLoc.TAKEOFF, decoded.opKind)
        }
    }

    @Test fun everyTruncationAndTrailingBytesAreRejectedAsAWhole() {
        val data = reference()
        for (end in 0 until data.size) assertTrue("length $end", OpenDroneIdNan.messages(data.copyOf(end)).isEmpty())
        assertTrue(OpenDroneIdNan.messages(data + byteArrayOf(0)).isEmpty())
        assertTrue(OpenDroneIdNan.messages(null).isEmpty())
        assertTrue(OpenDroneIdNan.messages(ByteArray(256)).isEmpty())
    }

    @Test fun unsupportedVersionSizeCountAndNestedPackAreRejected() {
        val mutations = listOf(
            1 to 0xF1, // Only protocol 2 has been validated.
            1 to 0xF3,
            2 to 24,
            2 to 26,
            3 to 0,
            3 to 9, // Count does not match available bytes.
            3 to 10, // Beyond the reference protocol's nine-message limit.
            4 to 0xF2, // Packs cannot contain packs.
            29 to 0x13, // Unsupported child version.
            54 to 0x62, // Reserved message type.
        )
        for ((offset, value) in mutations) {
            val malformed = reference().also { it[offset] = value.toByte() }
            assertTrue("offset $offset value $value", OpenDroneIdNan.messages(malformed).isEmpty())
            assertNull(OpenDroneId.fromFacts(RadioFacts(serviceData = listOf(
                ServiceDataRecord(OpenDroneId.NAN_SERVICE_NAME, malformed.toHexUpper()),
            ))).pin())
        }
    }

    @Test fun singleMessageUsesExactlyOneCounterWithoutBleOrOuiFraming() {
        val data = bytes("0D" + OpenDroneIdReferenceFixtures.LOCATION_B)
        assertEquals(listOf(OpenDroneIdReferenceFixtures.LOCATION_B), OpenDroneIdNan.messages(data).map { it.toHexUpper() })
        assertTrue(OpenDroneIdNan.messages(byteArrayOf(0x0D) + data).isEmpty())
        assertTrue(OpenDroneIdNan.messages(bytes("FA0BBC0D") + data).isEmpty())
    }

    @Test fun nanNormalizationForCatalogFieldsDoesNotPretendThereWasABlePacket() {
        val facts = RadioFacts(serviceData = listOf(ServiceDataRecord(OpenDroneId.NAN_SERVICE_NAME, reference().toHexUpper())))
        assertEquals(listOf(
            "0D0D" + OpenDroneIdReferenceFixtures.BASIC,
            "0D0D" + OpenDroneIdReferenceFixtures.LOCATION_A,
            "0D0D" + OpenDroneIdReferenceFixtures.SYSTEM_A,
        ), OpenDroneId.wifiFffaPayloads(facts))
        assertEquals(OpenDroneId.NAN_SERVICE_NAME, facts.serviceData.single().uuid)
        assertTrue(facts.vendorIes.isEmpty())
    }

    @Test fun duplicatePackFieldsCannotProduceConflictingNanEvidence() {
        val invalid = listOf(
            "F21902" + OpenDroneIdReferenceFixtures.LOCATION_A.repeat(2),
            "F21902" + OpenDroneIdReferenceFixtures.SYSTEM_A.repeat(2),
            "F21903" + OpenDroneIdReferenceFixtures.BASIC.repeat(3),
        )
        invalid.forEach { pack ->
            val payload = "00$pack"
            val facts = RadioFacts(serviceData = listOf(ServiceDataRecord(OpenDroneId.NAN_SERVICE_NAME, payload)))
            assertTrue(OpenDroneIdNan.messagesHex(payload).isEmpty())
            assertEquals(PayloadLocation(), OpenDroneId.fromFacts(facts))
            assertTrue(OpenDroneId.wifiFffaPayloads(facts).isEmpty())
        }
    }

    @Test fun maximumReferencePackHasTheSameNanDecodedAndNormalizedEvidence() {
        val payload = "00" + OpenDroneIdReferenceFixtures.PACK_MAX
        val facts = RadioFacts(serviceData = listOf(ServiceDataRecord(OpenDroneId.NAN_SERVICE_NAME, payload)))
        assertEquals(9, OpenDroneIdNan.messagesHex(payload).size)
        assertEquals(9, OpenDroneId.wifiFffaPayloads(facts).size)
        val decoded = OpenDroneId.fromFacts(facts)
        assertEquals(40.0, decoded.lat!!, 1e-7)
        assertEquals(39.9, decoded.opLat!!, 1e-7)
        assertEquals("1581F3YTDJ1D0031Z530", decoded.uasId)
    }

    @Test fun malformedHexAndCopiesAreSafe() {
        for (hex in listOf("0", "GG", " ", "0D" + "ZZ".repeat(25), "+D" + OpenDroneIdReferenceFixtures.LOCATION_B)) {
            assertTrue(OpenDroneIdNan.messagesHex(hex).isEmpty())
        }
        val data = reference()
        val messages = OpenDroneIdNan.messages(data)
        data.fill(0)
        assertEquals(OpenDroneIdReferenceFixtures.BASIC, messages.first().toHexUpper())
    }

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
