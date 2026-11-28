package app.fieldwatch.sweep.spectrum

import org.junit.Assert.*
import org.junit.Test

class TinySaProtocolTest {
    private val ultra = TinySaModel.TINYSA_ULTRA
    private val basic = TinySaModel.TINYSA_BASIC
    private val preset = SpectrumPreset.REMOTES
    private val body = IntArray(16) { 128 * 32 }.apply {
        this[0] = 0
        this[1] = 0x7b7d
        this[2] = 0x7d7b
        this[3] = 0xffff
    }
    private fun frame(raw: IntArray): ByteArray {
        val triples = ByteArray(3 * raw.size)
        raw.forEachIndexed { i, v ->
            triples[3*i] = 0x78
            triples[3*i+1] = v.toByte()
            triples[3*i+2] = (v ushr 8).toByte()
        }
        return byteArrayOf(0x7b) + triples + byteArrayOf(0x7d)
    }

    @Test fun bannerIdentificationRequiresAnAnalyzerMarkerAndPrefersUltra() {
        for (name in listOf("tinySA4", "tinySA4_v1.4", "TiNySa uLtRa", "device ZS-406", "zs-407")) {
            assertEquals(ultra, TinySaProtocol.parseVersionBanner("tinySA\r\n$name\r\nch>"))
        }
        assertEquals(basic, TinySaProtocol.parseVersionBanner("version\r\nTinySA\r\nch>"))
        assertNull(TinySaProtocol.parseVersionBanner("STM32 virtual COM port\r\nch>"))
    }

    @Test fun commandsRespectBothModelAndPointLimits() {
        assertEquals("scanraw 100000 6000000000 1024\r\n", TinySaProtocol.buildScanRawCommand(
            100000, ultra.maxHz, 1024, ultra).toString(Charsets.US_ASCII))
        TinySaProtocol.buildScanRawCommand(100000, basic.maxHz, 16, basic)
        for ((lo, hi, n) in listOf(Triple(99999L, 100001L, 16), Triple(100000L, 100000L, 16),
                Triple(100001L, 100000L, 16), Triple(100000L, basic.maxHz + 1, 16),
                Triple(100000L, 200000L, 15), Triple(100000L, 200000L, 1025))) {
            assertThrows(IllegalArgumentException::class.java) { TinySaProtocol.buildScanRawCommand(lo, hi, n, basic) }
        }
        assertEquals(8, SpectrumPreset.entries.distinctBy { it.id }.size)
        SpectrumPreset.entries.forEach {
            assertEquals(it.ultraOnly, it.stopHz > basic.maxHz)
            TinySaProtocol.buildScanRawCommand(it.startHz, it.stopHz, it.defaultPoints, ultra)
        }
    }

    @Test fun unsignedLittleEndianAndBracesSurviveEveryChunkBoundary() {
        val wire = TinySaProtocol.buildScanRawCommand(preset.startHz, preset.stopHz, body.size, ultra) +
            frame(body) + "\r\nch> ".toByteArray()
        for (split in 0..wire.size) {
            val decoder = TinySaProtocol.ScanRawDecoder(body.size, ultra)
            decoder.feed(wire, split)
            assertTrue(decoder.feed(wire.copyOfRange(split, wire.size)))
            val result = decoder.result()
            assertArrayEquals(intArrayOf(-1740, 8139, 8298, 18740, -460), result.take(5).toIntArray())
        }
        val decoder = TinySaProtocol.ScanRawDecoder(body.size, basic)
        wire.forEach { decoder.feed(byteArrayOf(it)) }
        assertEquals(-1280, decoder.result()[0])
        assertEquals(0, decoder.result()[4])
    }

    @Test fun requiresEveryPrefixAndTheClosingByteAndRejectsAllTruncations() {
        val raw = frame(body)
        for (n in 0 until raw.size) {
            val decoder = TinySaProtocol.ScanRawDecoder(16, ultra)
            assertFalse(decoder.feed(raw, n))
            assertThrows(IllegalArgumentException::class.java) { decoder.result() }
        }
        for (p in (0..15).map { 1 + 3*it } + raw.lastIndex) {
            val broken = raw.clone().apply { this[p] = 0x79 }
            assertThrows(IllegalArgumentException::class.java) { TinySaProtocol.ScanRawDecoder(16, ultra).feed(broken) }
        }
    }

    @Test fun mediansPeaksInterpolationAndWidthCutoffIncludeTheirBoundaries() {
        val levels = IntArray(20) { -900 }
        levels.fill(-780, 0, 5)
        levels[3] = -720
        levels[19] = -705
        val sweep = TinySaProtocol.analyze(levels, preset, basic, 123)
        assertEquals(-900, sweep.medianDeciDbm)
        assertEquals(-705, sweep.peakDeciDbm)
        assertEquals(preset.stopHz, sweep.peakHz)
        assertEquals(195, sweep.prominenceDeciDb)
        assertEquals(2, sweep.clusters.size)
        val first = sweep.clusters.first()
        assertEquals(preset.startHz, first.startHz)
        assertEquals(preset.startHz + (preset.stopHz - preset.startHz) * 4 / 19, first.endHz)
        assertEquals(preset.startHz + (preset.stopHz - preset.startHz) * 3 / 19, first.peakHz)
        assertEquals(180, first.prominenceDeciDb)
        levels[5] = -780
        assertEquals(listOf(preset.stopHz), TinySaProtocol.analyze(levels, preset, basic, 124).clusters.map { it.peakHz })
        assertTrue(TinySaProtocol.analyze(IntArray(17) { -700 }, preset, basic, 123).clusters.isEmpty())
        assertEquals(-850, TinySaProtocol.analyze(IntArray(16) { if (it < 8) -900 else -800 }, preset, basic, 123).medianDeciDbm)
        assertTrue(SpectrumDemo.sweep(preset).demo)
    }

    @Test fun rejectsFirmwareUsageOrErrorPromptImmediatelyWithoutBreakingValidStalePrompt() {
        val validWithStalePrompt = "ch> ".toByteArray() +
            TinySaProtocol.buildScanRawCommand(preset.startHz, preset.stopHz, body.size, ultra) +
            frame(body) + "\r\nch> ".toByteArray()
        val ok = TinySaProtocol.ScanRawDecoder(body.size, ultra)
        assertTrue(ok.feed(validWithStalePrompt))
        assertEquals(-1740, ok.result()[0])

        val usageReply = ("scanraw 860000000 928000000 16\r\nusage: scanraw {start(Hz)} {stop(Hz)} [points]\r\nch> ")
            .toByteArray(Charsets.US_ASCII)
        assertThrows(TinySaProtocol.ScanRawRejectedException::class.java) {
            TinySaProtocol.ScanRawDecoder(16, ultra).feed(usageReply)
        }

        val promptOnlyRejection = ("scanraw 860000000 928000000 16\r\nscanraw ?\r\nch> ")
            .toByteArray(Charsets.US_ASCII)
        assertThrows(TinySaProtocol.ScanRawRejectedException::class.java) {
            TinySaProtocol.ScanRawDecoder(16, ultra).feed(promptOnlyRejection)
        }
    }
}
