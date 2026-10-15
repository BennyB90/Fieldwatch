package app.fieldwatch.sweep

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class SweepRadioGateTest {
    @Test fun differentModesCannotReserveSimultaneously() {
        val gate = SweepRadioGate()
        assertTrue(gate.acquire("scan:first"))
        assertFalse(gate.acquire("travel:second"))
        assertFalse(gate.acquire("radar:third"))
        assertFalse(gate.release("scan:stale"))
        assertTrue(gate.held())
        assertTrue(gate.release("scan:first"))
        assertTrue(gate.acquire("travel:second"))
        assertFalse(gate.release("scan:first"))
        assertTrue(gate.held())
    }

    @Test fun racingModesHaveExactlyOneOwner() {
        val gate = SweepRadioGate()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val acquired = AtomicInteger()
        listOf("scan:a", "travel:b").forEach { token ->
            Thread {
                ready.countDown(); start.await()
                if (gate.acquire(token)) acquired.incrementAndGet()
                done.countDown()
            }.start()
        }
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, acquired.get())
    }
}
