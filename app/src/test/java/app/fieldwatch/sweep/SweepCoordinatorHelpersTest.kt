package app.fieldwatch.sweep

import app.fieldwatch.domain.OperatorLoc
import app.fieldwatch.domain.DefaultCatalog
import app.fieldwatch.domain.Observation
import app.fieldwatch.domain.OpenDroneIdReferenceFixtures
import app.fieldwatch.domain.PayloadLocation
import app.fieldwatch.domain.RadioFacts
import app.fieldwatch.domain.RadioKind
import app.fieldwatch.domain.ServiceDataRecord
import app.fieldwatch.data.DeviceStore
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SweepCoordinatorHelpersTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun retainedAircraftCoordinateKeepsItsOriginalTimeThroughBasicIdRotation() {
        val pin = SweepPin(SweepPinKind.AIRCRAFT, 40.0, -74.0, 1_000)
        val basicId = PayloadLocation(uasId = "TEST-SERIAL", basicId = true)
        assertEquals(1_000L, sweepPinObservationTime(pin, basicId, listOf(pin), 9_000))
    }

    @Test
    fun differentCoordinatesCannotMakeTheDisplayedCoordinateFresh() {
        val pin = SweepPin(SweepPinKind.AIRCRAFT, 40.0, -74.0, 1_000)
        val elsewhere = PayloadLocation(lat = 41.0, lon = -75.0)
        assertEquals(1_000L, sweepPinObservationTime(pin, elsewhere, listOf(pin), 9_000))
        assertNull(sweepPinObservationTime(pin.copy(observedAt = null), elsewhere, emptyList(), 9_000))
    }

    @Test
    fun matchingDecodedCoordinatesReceiveTheActualObservationTime() {
        val pin = SweepPin(SweepPinKind.AIRCRAFT, 40.0, -74.0)
        assertEquals(9_000L, sweepPinObservationTime(pin, PayloadLocation(lat = 40.0, lon = -74.0), emptyList(), 9_000))
    }

    @Test
    fun operatorCoordinateRequiresMatchingOperatorTypeAsWellAsPosition() {
        val station = SweepPin(SweepPinKind.CONTROL_STATION, 39.9, -73.9, 1_000)
        val takeoff = PayloadLocation(opLat = 39.9, opLon = -73.9, opKind = OperatorLoc.TAKEOFF)
        assertEquals(1_000L, sweepPinObservationTime(station, takeoff, listOf(station), 9_000))
        assertEquals(9_000L, sweepPinObservationTime(station, takeoff.copy(opKind = OperatorLoc.LIVE), listOf(station), 9_000))
    }

    @Test
    fun unknownCoordinateAgeStaysUnknownWhenOnlyAnotherMessageArrives() {
        val pin = SweepPin(SweepPinKind.TAKEOFF, 39.9, -73.9)
        assertNull(sweepPinObservationTime(pin, PayloadLocation(), listOf(pin), 9_000))
    }

    @Test
    fun rotatingSameDroneMessagesKeepCoordinatesAndSeparatePinAges() {
        val store = DeviceStore()
        val catalog = DefaultCatalog.fleets()
        val messages = listOf(
            OpenDroneIdReferenceFixtures.LOCATION_A,
            OpenDroneIdReferenceFixtures.BASIC,
            OpenDroneIdReferenceFixtures.SYSTEM_A,
            OpenDroneIdReferenceFixtures.BASIC,
            OpenDroneIdReferenceFixtures.LOCATION_B,
        )
        var previous: SweepFinding? = null
        val findings = messages.mapIndexed { index, payload ->
            sweepFindingForObservation(store, observation(payload, (index + 1) * 1_000L), catalog, previous)
                .also { previous = it }
        }
        assertEquals(40.0, findings[1].dronePins.single().latitude, 1e-7)
        assertEquals(1_000L, findings[1].dronePins.single().observedAt)
        assertEquals("1581F3YTDJ1D0031Z530", findings[1].droneId)
        assertEquals(1_000L, findings[3].dronePins.single { it.kind == SweepPinKind.AIRCRAFT }.observedAt)
        assertEquals(3_000L, findings[3].dronePins.single { it.kind == SweepPinKind.TAKEOFF }.observedAt)
        assertEquals(40.001, findings[4].dronePins.single { it.kind == SweepPinKind.AIRCRAFT }.latitude, 1e-7)
        assertEquals(5_000L, findings[4].dronePins.single { it.kind == SweepPinKind.AIRCRAFT }.observedAt)
        assertEquals(3_000L, findings[4].dronePins.single { it.kind == SweepPinKind.TAKEOFF }.observedAt)
    }

    @Test
    fun queuedCheckpointFinishAndDeleteCannotResurrectDeletedSession() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val queue = SweepPersistenceQueue(scope)
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val store = SweepSessionStore(temporary.newFolder())
        val sequence = mutableListOf<String>()
        val failure = AtomicReference<Throwable>()
        try {
            queue.enqueue(onFailure = { failure.set(it); complete.countDown() }) {
                blocked.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            val session = SweepSession(id = "ordered", startedAt = 1_000)
            queue.enqueue { store.save(session); sequence += "checkpoint" }
            queue.enqueue { store.save(session.copy(state = SweepSessionState.COMPLETE)); sequence += "finish" }
            queue.enqueue { store.delete(session.id); sequence += "delete" }
            queue.enqueue { complete.countDown() }
            release.countDown()
            assertTrue(complete.await(5, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals(listOf("checkpoint", "finish", "delete"), sequence)
            assertTrue(store.load().isEmpty())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun failedMutationReportsItsFailureAndDoesNotBlockFollowingDeletion() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val queue = SweepPersistenceQueue(scope)
        val complete = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val store = SweepSessionStore(temporary.newFolder())
        store.save(SweepSession(id = "old", startedAt = 1_000))
        try {
            queue.enqueue(onFailure = { failure.set(it) }) { throw IOException("write unavailable") }
            queue.enqueue { store.deleteAll(); complete.countDown() }
            assertTrue(complete.await(5, TimeUnit.SECONDS))
            assertEquals("write unavailable", failure.get()?.message)
            assertTrue(store.load().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    private fun observation(payload: String, at: Long) = Observation(
        kind = RadioKind.BLE,
        mac = "AA:BB:CC:DD:EE:01",
        name = "",
        rssi = -60,
        channel = 0,
        frequencyMhz = 2402,
        hiddenSsid = false,
        serviceUuids = listOf("FFFA"),
        manufacturerId = null,
        manufacturerDataHex = "",
        rawHex = "",
        extras = "",
        at = at,
        facts = RadioFacts(serviceData = listOf(ServiceDataRecord("FFFA", "0D00$payload"))),
    )
}
