package app.fieldwatch.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleScanEvidenceTest {
    private val ms = 1_000_000L

    @Test fun delayedDeliveryKeepsActualWallAndBootMeasurementTime() {
        val evidence = BleScanEvidence()
        val token = evidence.begin(10_000 * ms)
        val measured = evidence.measurement(token, "AA:BB", 11_250 * ms, 15_000 * ms, 100_000)!!
        assertEquals(96_250L, measured.wallAt)
        assertEquals(11_250L, measured.elapsedMs)
        assertEquals(3_750L, measured.ageMs)
    }

    @Test fun replayAndOutOfOrderDeliveryAreNotNewSamples() {
        val evidence = BleScanEvidence()
        val token = evidence.begin(10_000 * ms)
        assertEquals(99_000L, evidence.measurement(token, "aa:bb", 11_000 * ms, 12_000 * ms, 100_000)?.wallAt)
        assertNull(evidence.measurement(token, "AA:BB", 11_000 * ms, 15_000 * ms, 103_000))
        assertNull(evidence.measurement(token, "AA:BB", 10_500 * ms, 15_000 * ms, 103_000))
        // The same payload can be genuinely observed again at a later radio timestamp.
        assertEquals(102_000L, evidence.measurement(token, "AA:BB", 14_000 * ms, 15_000 * ms, 103_000)?.wallAt)
    }

    @Test fun invalidFutureAndPreSessionPacketsCannotPoisonValidEvidence() {
        val evidence = BleScanEvidence()
        val token = evidence.begin(10_000 * ms)
        for (sample in listOf(-1L, 0L, 9_999 * ms, 12_001 * ms, Long.MAX_VALUE)) {
            assertNull("sample $sample", evidence.measurement(token, "A", sample, 12_000 * ms, 100_000))
        }
        assertNull(evidence.measurement(token, "", 11_000 * ms, 12_000 * ms, 100_000))
        assertNull(evidence.measurement(token, "A", 11_000 * ms, 12_000 * ms, 1))
        assertEquals(99_000L, evidence.measurement(token, "A", 11_000 * ms, 12_000 * ms, 100_000)?.wallAt)
    }

    @Test fun stoppedCallbacksCannotCrossAStartedGenerationEvenWithANewTimestamp() {
        val evidence = BleScanEvidence()
        val old = evidence.begin(10_000 * ms)
        evidence.cancel()
        assertFalse(evidence.accepts(old))
        assertNull(evidence.measurement(old, "A", 11_000 * ms, 12_000 * ms, 100_000))
        val restarted = evidence.begin(13_000 * ms)
        assertTrue(evidence.accepts(restarted))
        assertNull(evidence.measurement(old, "A", 14_000 * ms, 15_000 * ms, 103_000))
        assertNull(evidence.measurement(restarted, "A", 11_000 * ms, 15_000 * ms, 103_000))
        assertEquals(102_000L, evidence.measurement(restarted, "A", 14_000 * ms, 15_000 * ms, 103_000)?.wallAt)
    }

    @Test fun boundedKeysDoNotEvictReplayEvidenceAndResetOnRestart() {
        val evidence = BleScanEvidence(maxKeys = 2)
        val token = evidence.begin(10_000 * ms)
        for (address in listOf("A", "B")) assertEquals(11_000L, evidence.measurement(token, address, 11_000 * ms, 12_000 * ms, 100_000)?.elapsedMs)
        assertNull(evidence.measurement(token, "C", 11_000 * ms, 12_000 * ms, 100_000))
        assertNull(evidence.measurement(token, "A", 11_000 * ms, 12_000 * ms, 100_000))
        assertEquals(11_500L, evidence.measurement(token, "A", 11_500 * ms, 12_000 * ms, 100_000)?.elapsedMs)
        val restarted = evidence.begin(13_000 * ms)
        assertEquals(14_000L, evidence.measurement(restarted, "C", 14_000 * ms, 15_000 * ms, 103_000)?.elapsedMs)
    }

    @Test fun wallClockAdjustmentCannotInventElapsedObservationTime() {
        val evidence = BleScanEvidence()
        val token = evidence.begin(10_000 * ms)
        val before = evidence.measurement(token, "A", 11_000 * ms, 12_000 * ms, 100_000)!!
        val after = evidence.measurement(token, "A", 12_000 * ms, 13_000 * ms, 90_000)!!
        assertEquals(1_000L, after.elapsedMs - before.elapsedMs)
        assertTrue(after.wallAt < before.wallAt)
    }
}
