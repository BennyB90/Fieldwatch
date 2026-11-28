package app.fieldwatch.sweep.thermal

import org.junit.Assert.*
import org.junit.Test

class UvcProtocolTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun iface(number: Int, alt: Int, subclass: Int) = bytes(9, 4, number, alt, 1, 14, subclass, 0, 0)
    private fun endpoint(alt: Int, raw: Int) = iface(1, alt, 2) + bytes(7, 5, 0x81, 1, raw and 255, raw shr 8, 1)
    private fun ByteArray.u32(offset: Int, value: Int) { for (i in 0..3) this[offset + i] = (value shr (i * 8)).toByte() }
    private fun format(code: String, index: Int = 1) = ByteArray(27).apply {
        this[0] = 27; this[1] = 0x24; this[2] = 4; this[3] = index.toByte(); this[4] = 2
        code.toByteArray().copyInto(this, 5)
        bytes(0, 0, 0x10, 0, 0x80, 0, 0, 0xaa, 0, 0x38, 0x9b, 0x71).copyInto(this, 9)
        this[21] = 16
    }
    private fun frame(id: Int, width: Int, height: Int, subtype: Int = 5) = ByteArray(34).apply {
        bytes(34, 0x24, subtype, id, 0, width and 255, width shr 8, height and 255, height shr 8).copyInto(this)
        u32(17, width * height * 2)
        u32(21, 666666)
        this[25] = 2
        u32(26, 666666); u32(30, 400000)
    }
    private fun descriptors() = bytes(8, 11, 0, 3, 14, 3, 0, 0) +
        iface(0, 0, 1) + bytes(14, 0x24, 1, 0x10, 1, 0, 0, 0, 0, 0, 0, 2, 1, 2) +
        iface(1, 0, 2) + bytes(13, 0x24, 1, 1, 0, 0, 0x81, 0, 0, 0, 0, 0, 0) +
        format("YUY2") + frame(1, 256, 192) + frame(2, 256, 384) +
        format("Y16 ", 2) + frame(1, 256, 192) +
        endpoint(1, 0x400) + endpoint(2, 0xc00) + endpoint(3, 0x1400) +
        iface(2, 0, 2) + bytes(11, 0x24, 6, 1, 1, 0, 0, 0, 0, 0, 0) +
        frame(1, 1280, 720, 7) + bytes(7, 5, 0x83, 2, 0, 2, 0)

    @Test fun resolvesTwoStreamingInterfacesAndChoosesSmallestAdequateAlternate() {
        val catalog = UvcProtocol.parse(descriptors())
        assertEquals(setOf(0), catalog.controlInterfaces)
        assertEquals(2, catalog.streams.size)
        val (stream, mode) = catalog.thermal()!!
        assertEquals(0, stream.controlInterface)
        assertEquals(384, mode.height)
        assertEquals(400000L, mode.interval)
        assertEquals(0x110, stream.version)
        assertEquals(listOf("YUY2", "YUY2", "Y16"), stream.modes.map { it.format })
        assertEquals(2, stream.endpoint(1200)!!.alt)
        assertEquals(3072, stream.endpoint(3072)!!.capacity)
        assertNull(stream.endpoint(3073))
        assertTrue(catalog.secondaryVisible(stream))
        assertEquals(UvcTransport.BULK, catalog.streams.last().endpoint(65536)!!.transport)
    }

    @Test fun skipsInvalidGuidsShortDescriptorsAndUnrelatedInterfaces() {
        val bad = format("YUY2").apply { this[10] = 9 }
        val fake = iface(3, 0, 2) + bad + frame(1, 256, 384) + bytes(7, 5, 0x81, 2, 0, 2, 0)
        assertNull(UvcProtocol.parse(fake).thermal())
        val data = descriptors()
        for (end in 0..data.size) UvcProtocol.parse(data.copyOf(end))
        assertEquals(2, UvcProtocol.parse(data + bytes(0, 4, 8)).streams.size)
        assertNull(UvcProtocol.parse(iface(2, 0, 2) + format("Y16 ") + frame(1, 256, 384)).thermal())
    }

    @Test fun negotiatesAllThreeBlockSizesAndCommitsExactDeviceResponse() {
        val (stream, mode) = UvcProtocol.parse(descriptors()).thermal()!!
        for (size in listOf(26, 34, 48)) {
            val calls = mutableListOf<Triple<Int, Int, Int>>()
            var accepted = byteArrayOf()
            val (actual, result) = UvcProtocol.negotiate(stream, mode) { direction, code, control, data ->
                calls += Triple(direction, code, control)
                when {
                    code == UvcProtocol.GET_LEN -> data[0] = size.toByte()
                    code == UvcProtocol.GET_CUR -> {
                        data.u32(18, 196608); data.u32(22, 1950)
                        data[16] = 36
                        data[data.lastIndex] = if (size > 26) 7 else 0
                        accepted = data.copyOf()
                    }
                    control == UvcProtocol.COMMIT -> assertArrayEquals(accepted, data)
                    else -> {
                        assertEquals(size, data.size)
                        assertArrayEquals(bytes(1, 0, 1, 2, 0x80, 0x1a, 6, 0), data.copyOf(8))
                    }
                }
                data.size
            }
            assertEquals(listOf(Triple(0xa1, 0x85, 1), Triple(0x21, 1, 1), Triple(0xa1, 0x81, 1), Triple(0x21, 1, 2)), calls)
            assertEquals(1950L, result.payloadBytes)
            assertEquals(mode, actual)
        }
    }

    @Test fun fallsBackToAdvertisedImageOnlyAndRejectsWrongBufferSizesBeforeCommit() {
        val (stream, mode) = UvcProtocol.parse(descriptors()).thermal()!!
        val accepted = UvcProtocol.negotiate(stream, mode) { _, code, control, data ->
            when {
                code == UvcProtocol.GET_LEN -> -1
                control == UvcProtocol.PROBE && data[3].toInt() == 2 -> -1
                code == UvcProtocol.GET_CUR -> {
                    data.u32(18, 98304); data.u32(22, 1024)
                    data.size
                }
                else -> data.size
            }
        }
        assertEquals(192, accepted.first.height)
        assertThrows(IllegalStateException::class.java) {
            UvcProtocol.negotiate(stream, mode) { _, code, selector, data ->
                assertNotEquals(UvcProtocol.COMMIT, selector)
                if (code == UvcProtocol.GET_LEN) -1 else data.size
            }
        }
    }

    @Test fun assemblesVariableHeadersAndFidOrEofBoundariesWithoutJoiningFrames() {
        val frames = mutableListOf<ByteArray>()
        val assembler = UvcReassembler(4) { frames.add(it) }
        assembler.packet(bytes(3, 0, 0xaa, 1, 2))
        assembler.packet(bytes(2, 0, 3, 4))
        assembler.packet(bytes(2, 3, 5, 6, 7, 8, 99), 6)
        assembler.packet(bytes(2, 3))
        assembler.packet(byteArrayOf())
        assertEquals(2, frames.size)
        assertArrayEquals(bytes(1, 2, 3, 4), frames.first())
        assertArrayEquals(bytes(5, 6, 7, 8), frames.last())
        assertEquals(0L, assembler.dropped)
    }

    @Test fun countsCorruptionShortFramesAndOverflowAndResumesAtNextFid() {
        val frames = mutableListOf<ByteArray>()
        val a = UvcReassembler(2) { frames.add(it) }
        a.packet(bytes(2, 0, 1))
        a.packet(bytes(7, 0))
        a.packet(bytes(2, 2, 2))
        a.packet(bytes(2, 0x43, 3, 4))
        a.packet(bytes(2, 2, 5))
        a.packet(bytes(2, 3, 1, 2, 3))
        a.packet(bytes(2, 2, 7, 8))
        assertEquals(4L, a.dropped)
        assertEquals(3L, a.corruptFrames)
        assertArrayEquals(bytes(7, 8), frames.single())
    }

    @Test fun checksOptionalTimestampAndClockFieldsBeforeDeliveringPixels() {
        val frames = mutableListOf<ByteArray>()
        val a = UvcReassembler(2) { frames.add(it) }
        a.packet(bytes(2, 0x0e, 1, 2))
        a.packet(bytes(12, 0x0f, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12))
        assertEquals(1L, a.corruptFrames)
        assertArrayEquals(bytes(11, 12), frames.single())
    }

    @Test fun fallsBackTo26ByteControlWhenUvc11DeviceStalls34ByteProbeWithoutGetLen() {
        val (stream, mode) = UvcProtocol.parse(descriptors()).thermal()!!
        val (actual, negotiated) = UvcProtocol.negotiate(stream, mode) { _, code, selector, data ->
            when {
                code == UvcProtocol.GET_LEN -> -1
                selector == UvcProtocol.PROBE && data.size == 34 -> -1
                code == UvcProtocol.GET_CUR -> {
                    assertEquals(26, data.size)
                    data.u32(18, 196608); data.u32(22, 1950)
                    data.size
                }
                else -> {
                    assertEquals(26, data.size)
                    data.size
                }
            }
        }
        assertEquals(mode, actual)
        assertEquals(1950L, negotiated.payloadBytes)
    }

    @Test fun bulkAccumulatorReassemblesMulti16KbUsbfsChunksIntoSingleUvcPayload() {
        val frames = mutableListOf<ByteArray>()
        val reassembler = UvcReassembler(196608) { frames.add(it) }
        val accumulator = UvcBulkAccumulator(196620) { payload, total -> reassembler.packet(payload, total) }
        val wire = ByteArray(196620).apply {
            this[0] = 12
            this[1] = 0x02 // EOF=1, FID=0
            for (i in 12 until size) this[i] = ((i * 7) and 0x7f).toByte() // include 0x7f bytes that would look like ERR|EOF|FID if misparsed as headers
        }
        var pos = 0
        while (pos < wire.size) {
            val chunk = minOf(accumulator.nextReadSize(), wire.size - pos)
            wire.copyInto(accumulator.buffer, accumulator.offset, pos, pos + chunk)
            pos += chunk
            accumulator.onTransfer(chunk)
        }
        assertEquals(1, frames.size)
        assertEquals(0L, reassembler.dropped)
        assertEquals(0L, reassembler.corruptFrames)
        assertArrayEquals(wire.copyOfRange(12, wire.size), frames.single())
    }
}
