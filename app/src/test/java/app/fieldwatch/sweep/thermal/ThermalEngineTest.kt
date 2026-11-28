package app.fieldwatch.sweep.thermal

import org.junit.Assert.*
import org.junit.Test

class ThermalEngineTest {
    private val pixels = 256 * 192
    private val device = ThermalDevice(1, "USB")
    private fun combined(raw: (Int) -> Int) = ByteArray(ThermalFrameParser.COMBINED_BYTES).apply {
        for (i in 0 until pixels) {
            this[i * 2] = (i % 251).toByte()
            this[i * 2 + 1] = 127
            val at = ThermalFrameParser.IMAGE_BYTES + 2 * i
            val u = raw(i)
            this[at] = u.toByte()
            this[at + 1] = (u ushr 8).toByte()
        }
    }

    @Test fun exactLittleEndianTemperaturesAndTrueEvenMedian() {
        val bytes = combined { if (it % 2 == 0) 18857 else 20003 }
        val frame = ThermalFrameParser.parse(bytes, 123)!!
        assertTrue(frame.radiometric)
        assertEquals(18857 / 64f - 273.15f, frame.minC!!, 0.0001f)
        assertEquals(20003 / 64f - 273.15f, frame.maxC!!, 0.0001f)
        assertEquals((18857 + 20003) / 128f - 273.15f, frame.ambientMedianC!!, 0.0001f)
        assertEquals(frame.maxC!! - frame.ambientMedianC!!, frame.spotDeltaC(1, 0)!!, 0.0001f)
        assertEquals(frame.spotDeltaC(1, 0), frame.maxDeltaC)
        assertEquals(123L, frame.measuredCheck(device)!!.capturedAt)
    }

    @Test fun rotatesLuminanceAndTemperatureTogetherInAllFourOrientations() {
        val bytes = combined { if (it == 1) 20000 else 18800 }
        for ((rotation, x, y) in listOf(Triple(0, 1, 0), Triple(90, 191, 1), Triple(180, 254, 191), Triple(270, 0, 254))) {
            val f = ThermalFrameParser.parse(bytes, 1, rotation)!!
            assertEquals(if (rotation % 180 == 0) 256 else 192, f.width)
            assertEquals(1, f.luminance[y * f.width + x].toInt())
            assertEquals(f.maxC, f.spotC(x, y))
            assertNull(f.spotC(f.width, y))
        }
    }

    @Test fun shutterAndWrongLengthCannotProduceMeasurements() {
        val shutter = combined { 18800 }.apply { this[lastIndex] = 1 }
        assertTrue(ThermalFrameParser.isShutter(shutter))
        assertNull(ThermalFrameParser.parse(shutter, 19))
        assertThrows(IllegalArgumentException::class.java) { ThermalFrameParser.parse(shutter.copyOf(100), 19) }
    }

    @Test fun imageOnlyIgnoresChromaAndShutterMarkerAndCannotBeAttached() {
        val bytes = combined { 18800 }.copyOf(ThermalFrameParser.IMAGE_BYTES).apply { this[lastIndex] = 1 }
        val f = ThermalFrameParser.parse(bytes, 100)!!
        assertFalse(f.radiometric)
        assertNull(f.ambientMedianC)
        assertNull(f.maxDeltaC)
        assertNull(f.spotC(1, 0))
        assertNull(f.measuredCheck(device))
        val delta = ThermalSpotlight.render(f, ThermalPalette.DELTA_SPOTLIGHT)
        assertEquals(0, delta.compactClusters)
        assertArrayEquals(ThermalSpotlight.render(f, ThermalPalette.WHITE_HOT).argb, delta.argb)
        assertEquals(0xff010101.toInt(), delta.argb[1])
    }

    @Test fun invalidSamplesAreExcludedFromMedianAndSpots() {
        val bytes = combined { when (it) { 0 -> 18000; 1 -> 20000; 2 -> 19000; 3 -> 65535; else -> 0 } }
        val f = ThermalFrameParser.parse(bytes, 2)!!
        assertEquals(19000f / 64 - 273.15f, f.ambientMedianC!!, .0001f)
        assertNull(f.spotC(3, 0))
        assertNull(f.spotDeltaC(4, 0))
        assertNull(ThermalFrameParser.parse(combined { 0 }, 2)!!.measuredCheck(device))
    }

    @Test fun spotlightSeparatesCompactRegionsFromBroadSurfaces() {
        val f = ThermalFrameParser.parse(combined { if (it % 256 < 50 || it == 128) 19400 else 18800 }, 1)!!
        val rendering = ThermalSpotlight.render(f, ThermalPalette.DELTA_SPOTLIGHT)
        val gray = ThermalSpotlight.render(f, ThermalPalette.WHITE_HOT)
        assertEquals(1, rendering.broadSurfaces)
        assertEquals(1, rendering.compactClusters)
        assertEquals(gray.argb[0], rendering.argb[0])
        assertEquals(gray.argb[80], rendering.argb[80])
        assertNotEquals(gray.argb[128], rendering.argb[128])
    }

    @Test fun deterministicSampleHasAnAdjustableSpotAndNoSavePath() {
        assertArrayEquals(ThermalDemo.bytes(), ThermalDemo.bytes())
        for (rotation in listOf(0, 90, 180, 270)) {
            val f = ThermalDemo.frame(rotation)
            assertTrue(f.demo)
            assertNull(f.copy(capturedAt = 100).measuredCheck(device))
            assertEquals(5.6f, f.maxDeltaC!!, .02f)
            assertEquals(1, ThermalSpotlight.render(f, ThermalPalette.DELTA_SPOTLIGHT, 1.5f).compactClusters)
            assertEquals(0, ThermalSpotlight.render(f, ThermalPalette.DELTA_SPOTLIGHT, 8f).compactClusters)
            assertFalse(ThermalSpotlight.render(f, ThermalPalette.IRONBOW).argb.contentEquals(
                ThermalSpotlight.render(f, ThermalPalette.WHITE_HOT).argb))
        }
    }
}
