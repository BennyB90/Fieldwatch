package app.fieldwatch.sweep.radar

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValentineEspProtocolTest {
    // Exact manufacturer ESP Specification 3_003.pdf p. 6 display vectors, pinned
    // at ValentineResearch/AndroidESPLibrary 7e2c8492b18f798b8b8345bebf83281627f738ce.
    // These are specification/replay fixtures, never claimed as hardware captures.
    private val checksummedDisplay = hex("AA D8 EA 31 09 5B 5B 1F 38 28 0C 00 00 E7 AB")
    private val nonChecksummedDisplay = hex("AA D8 E9 31 08 5B 5B 1F 38 28 0C 00 00 AB")

    @Test
    fun officialDisplayVectorsDecodeTheSameVisibleData() {
        val with = ValentineEspProtocol.decode(checksummedDisplay)!!
        val without = ValentineEspProtocol.decode(nonChecksummedDisplay)!!
        assertTrue(with.withChecksum)
        assertFalse(without.withChecksum)
        val display = ValentineEspProtocol.display(with, 1_000)!!
        assertEquals(display, ValentineEspProtocol.display(without, 1_000))
        assertEquals("2", display.bogeyText)
        assertEquals(5, display.signalBars)
        assertEquals(listOf(RadarBand.X), display.bands)
        assertEquals(listOf(RadarDirection.FRONT), display.directions)
        assertTrue(display.systemStatus)
        assertFalse(display.muted)
        assertEquals(1_000L, display.receivedAt)
    }

    @Test
    fun badFramingDeclaredLengthAndChecksumNeverProduceDisplayData() {
        val invalid = listOf(
            checksummedDisplay.copyOf().apply { this[0] = 0x00 },
            checksummedDisplay.copyOf().apply { this[lastIndex] = 0x00 },
            checksummedDisplay.copyOf().apply { this[1] = 0x00 },
            checksummedDisplay.copyOf().apply { this[2] = 0x00 },
            checksummedDisplay.copyOf().apply { this[4] = 0x08 },
            checksummedDisplay.copyOf().apply { this[lastIndex - 1] = 0x00 },
            checksummedDisplay.copyOf(10),
            checksummedDisplay + byteArrayOf(0),
        )
        invalid.forEach { assertNull(ValentineEspProtocol.decode(it)) }
    }

    @Test
    fun printedAlertExampleWithInconsistentLengthIsRejectedRatherThanSilentlyRepaired() {
        // Official PDF p.30 prints PL=07 for 7 data bytes plus a checksum (needs PL=08).
        // Its printed E8 checksum is also inconsistent with the summation rule on p.6.
        assertNull(ValentineEspProtocol.decode(hex("AA D6 EA 43 07 13 29 1D 21 85 88 00 E8 AB")))
    }

    @Test
    fun reconstructedReplayAlertsKeepExactMhzRawStrengthAndPriority() {
        // Reconstructed from PDF Table 9.3 payload values, with proper PL/checksum.
        val data = replayAlert(3, 3, 34_700, 0xB6, 0x81, 0x22, true)
        val decoded = ValentineEspProtocol.alert(ValentineEspProtocol.decode(data)!!, 2_000)!!
        assertEquals(3, decoded.index)
        assertEquals(3, decoded.count)
        assertEquals(34_700, decoded.frequencyMhz)
        assertEquals(182, decoded.frontStrength)
        assertEquals(129, decoded.rearStrength)
        assertEquals(listOf(RadarBand.KA), decoded.bands)
        assertEquals(listOf(RadarDirection.FRONT), decoded.directions)
        assertTrue(decoded.priority)
    }

    @Test
    fun noAlertReplayResetsACompleteTableWithoutInventingLaserAlerts() {
        val assembler = ValentineAlertTableAssembler()
        val one = alert(1, 1)
        assertEquals(listOf(one), assembler.accept(one, 0))
        val zero = ValentineEspProtocol.alert(ValentineEspProtocol.decode(replayAlert(0, 0, 0, 0, 0, 0, false))!!, 2_000)!!
        assertEquals(emptyList<RadarAlert>(), assembler.accept(zero, 1_000))
    }

    @Test
    fun packetTypesAndMalformedAlertIndicesCannotBeConfused() {
        val display = ValentineEspProtocol.decode(checksummedDisplay)!!
        assertNull(ValentineEspProtocol.alert(display, 1_000))
        val malformed = replayAlert(4, 3, 34_700, 1, 1, 0x22, false)
        assertNull(ValentineEspProtocol.alert(ValentineEspProtocol.decode(malformed)!!, 1_000))
        assertNull(ValentineEspProtocol.display(ValentineEspProtocol.decode(replayAlert(1, 1, 34_700, 1, 1, 0x22, true))!!, 1_000))
    }

    @Test
    fun laserIsReportedFromDisplayAndNotTheRadarOnlyAlertTable() {
        val display = ValentineEspProtocol.decode(checksummedDisplay)!!.copy(payload = listOf(0x06, 0x06, 0xFF, 0x81, 0x81, 0x0D, 0, 0))
        val mapped = ValentineEspProtocol.display(display, 1_000)!!
        assertEquals(listOf(RadarBand.LASER), mapped.bands)
        assertEquals(listOf(RadarDirection.REAR), mapped.directions)
        assertEquals(8, mapped.signalBars)
        assertTrue(mapped.muted)
        val table = ValentineEspProtocol.alert(ValentineEspProtocol.decode(replayAlert(1, 1, 0, 0, 0, 0x01, false))!!, 1_000)!!
        assertTrue(table.bands.isEmpty())
    }

    @Test
    fun outboundFactoryEmitsOnlyTheReadStreamSubscriptionForBothChecksumModes() {
        assertTrue(hex("AA DA E6 41 01 AC AB").contentEquals(ValentineEspProtocol.startAlertRequest(true)))
        assertTrue(hex("AA D9 E6 41 00 AB").contentEquals(ValentineEspProtocol.startAlertRequest(false)))
    }

    @Test
    fun selfAddressedBridgeRejectionRequiresAnExplicitKnownChecksumMode() {
        // Replay constructed from Bluetooth addendum p.4 + ESP p.31 definitions.
        val checksummed = hex("AA D6 E6 65 02 41 0E AB")
        val plain = hex("AA D6 E6 65 01 41 AB")
        assertNull(ValentineEspProtocol.decode(checksummed))
        assertEquals(listOf(0x41), ValentineEspProtocol.decodeControl(checksummed, true)!!.payload)
        assertEquals(listOf(0x41), ValentineEspProtocol.decodeControl(plain, false)!!.payload)
        assertNull(ValentineEspProtocol.decodeControl(checksummed, false))
        assertNull(ValentineEspProtocol.decodeControl(plain, true))
        assertNull(ValentineEspProtocol.decodeControl(checksummed.copyOf().apply { this[6] = 0 }, true))
    }

    @Test
    fun bridgeControlDecoderCannotAdmitDisplayAlertOrForeignSourceFrames() {
        val native = ValentineEspProtocol.decode(checksummedDisplay)!!
        assertNull(ValentineEspProtocol.decodeControl(checksummedDisplay, true))
        assertNull(ValentineEspProtocol.display(native.copy(source = 6), 1_000))
        assertNull(ValentineEspProtocol.alert(ValentineEspProtocol.decode(replayAlert(1, 1, 34_700, 1, 1, 0x22, true))!!.copy(source = 6), 1_000))
    }

    @Test
    fun completeTablesPublishOnlyAfterEveryDistinctIndexArrives() {
        val assembler = ValentineAlertTableAssembler()
        assertNull(assembler.accept(alert(2, 3), 0))
        assertNull(assembler.accept(alert(1, 3), 100))
        assertNull(assembler.accept(alert(3, 3), 200))
        val table = assembler.accept(alert(2, 3), 300)!!
        assertEquals(listOf(1, 2, 3), table.map { it.index })
        assertNull(assembler.accept(alert(3, 3), 400))
    }

    @Test
    fun missingOrChangedCyclesDoNotCombineStaleAlertsIntoACompleteTable() {
        val assembler = ValentineAlertTableAssembler()
        assembler.accept(alert(1, 2), 0)
        assertNull(assembler.accept(alert(2, 2), 2_001))
        assembler.accept(alert(1, 3), 3_000)
        assertNull(assembler.accept(alert(2, 2), 3_100))
        assertNull(assembler.accept(alert(3, 3), 3_200))
        assertEquals(listOf(alert(1, 1)), assembler.accept(alert(1, 1), 3_300))
    }

    @Test
    fun malformedAndOversizedBleDataCannotCrashTheBoundedDecoder() {
        val random = Random(41)
        repeat(500) { ValentineEspProtocol.decode(random.nextBytes(random.nextInt(0, 300))) }
        assertNull(ValentineEspProtocol.decode(ByteArray(21)))
        assertNotNull(ValentineEspProtocol.decode(checksummedDisplay))
    }

    private fun alert(index: Int, count: Int) = RadarAlert(index, count, 34_700, 120, 20,
        listOf(RadarBand.KA), listOf(RadarDirection.FRONT), false, 1_000)

    /** Independent replay construction follows the published length and summation rules. */
    private fun replayAlert(index: Int, count: Int, mhz: Int, front: Int, rear: Int, band: Int, priority: Boolean): ByteArray {
        val data = listOf(0xAA, 0xD6, 0xEA, 0x43, 8, (index shl 4) or count, mhz shr 8, mhz and 0xFF,
            front, rear, band, if (priority) 0x80 else 0)
        return (data + (data.sum() and 0xFF) + 0xAB).map { it.toByte() }.toByteArray()
    }

    private fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
