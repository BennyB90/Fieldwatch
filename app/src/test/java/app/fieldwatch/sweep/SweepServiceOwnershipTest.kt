package app.fieldwatch.sweep

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepServiceOwnershipTest {
    @Test
    fun finishedReportCannotStartAnotherScannerBeforeItsServiceIsDestroyed() {
        val ownership = SweepServiceOwnership()
        assertTrue(ownership.reserve("first"))
        assertTrue(ownership.claim("first", "service-one"))
        assertTrue(ownership.started("first", "service-one"))
        // Publishing COMPLETE changes the report, not the lifetime of this lease.
        assertFalse(ownership.reserve("second"))
        assertTrue(ownership.release("first", "service-one"))
        assertTrue(ownership.reserve("second"))
        assertTrue(ownership.claim("second", "service-two"))
        assertFalse(ownership.owns("first", "service-one"))
        assertFalse(ownership.started("first", "service-one"))
        assertFalse(ownership.release("first", "service-one"))
        assertTrue(ownership.owns("second", "service-two"))
    }

    @Test
    fun failedStartBeforeServiceCreationReleasesTheRequestImmediately() {
        val ownership = SweepServiceOwnership()
        assertTrue(ownership.reserve("failed"))
        assertTrue(ownership.releaseUncreated("failed"))
        assertFalse(ownership.isHeld())
        assertTrue(ownership.reserve("retry"))
        assertFalse(ownership.claim("failed", "late-service"))
        assertTrue(ownership.claim("retry", "new-service"))
    }

    @Test
    fun createdButUnstartedServiceRetainsItsLeaseUntilItsOwnCleanup() {
        val ownership = SweepServiceOwnership()
        assertTrue(ownership.reserve("slow-start"))
        assertEquals("slow-start", ownership.attachPending("service-one"))
        assertTrue(ownership.awaitingStart("slow-start"))
        assertFalse(ownership.releaseUncreated("slow-start"))
        assertFalse(ownership.reserve("retry"))
        assertTrue(ownership.release("slow-start", "service-one"))
        assertTrue(ownership.reserve("retry"))
    }

    @Test
    fun startupWatchdogCannotExpireAServiceThatAlreadyStarted() {
        val ownership = SweepServiceOwnership()
        ownership.reserve("running")
        ownership.claim("running", "service-one")
        assertTrue(ownership.awaitingStart("running"))
        ownership.started("running", "service-one")
        assertFalse(ownership.awaitingStart("running"))
        assertFalse(ownership.releaseUncreated("running"))
        assertTrue(ownership.owns("running", "service-one"))
    }

    @Test
    fun duplicateServiceInstanceCannotClaimOrReleaseAnotherInstancesSession() {
        val ownership = SweepServiceOwnership()
        ownership.reserve("session")
        assertTrue(ownership.claim("session", "service-one"))
        assertNull(ownership.attachPending("service-two"))
        assertFalse(ownership.claim("session", "service-two"))
        assertFalse(ownership.release("session", "service-two"))
        assertTrue(ownership.owns("session", "service-one"))
    }

    @Test
    fun delayedDestroyCannotReleaseNewInstanceEvenIfASessionIdIsReused() {
        val ownership = SweepServiceOwnership()
        ownership.reserve("same")
        ownership.claim("same", "old-instance")
        ownership.release("same", "old-instance")
        ownership.reserve("same")
        ownership.claim("same", "new-instance")
        assertFalse(ownership.release("same", "old-instance"))
        assertFalse(ownership.started("same", "old-instance"))
        assertTrue(ownership.owns("same", "new-instance"))
    }

    @Test
    fun simultaneousStartRequestsAcquireExactlyOneLease() {
        val ownership = SweepServiceOwnership()
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { id -> executor.submit(Callable {
                check(release.await(5, TimeUnit.SECONDS))
                ownership.reserve("session-$id")
            }) }
            release.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
            assertTrue(ownership.isHeld())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
