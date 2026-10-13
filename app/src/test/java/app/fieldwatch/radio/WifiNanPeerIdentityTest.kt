package app.fieldwatch.radio

import app.fieldwatch.data.DeviceStore
import app.fieldwatch.domain.Observation
import app.fieldwatch.domain.OpenDroneId
import app.fieldwatch.domain.OpenDroneIdReferenceFixtures
import app.fieldwatch.domain.PayloadLocation
import app.fieldwatch.domain.RadioFacts
import app.fieldwatch.domain.RadioKind
import app.fieldwatch.domain.ServiceDataRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiNanPeerIdentityTest {
    private val receiver = "0123456789abcdef0123456789abcdef"

    @Test fun fixedWidthGenerationAndPeerSurviveActualDeviceStoreNormalization() {
        val store = DeviceStore()
        // The former variable-width decimal representation really did alias in this store.
        assertEquals(store.keyOf(RadioKind.WIFI, "NAN:$receiver:1:31"),
            store.keyOf(RadioKind.WIFI, "NAN:$receiver:13:1"))
        assertNotEquals(store.keyOf(RadioKind.WIFI, WifiNanPeerIdentity.address(receiver, 1, 31)),
            store.keyOf(RadioKind.WIFI, WifiNanPeerIdentity.address(receiver, 13, 1)))
        assertNotEquals(store.keyOf(RadioKind.WIFI, WifiNanPeerIdentity.address(receiver, 1, 31)),
            store.keyOf(RadioKind.WIFI, WifiNanPeerIdentity.address(receiver, 1, 32)))
    }

    @Test fun aLaterSessionCannotInheritAnotherPeersStickyDroneIdOrOperatorPin() {
        val store = DeviceStore()
        val previous = store.ingest(observation(1, 31, OpenDroneIdReferenceFixtures.PACK_A, 1_000), emptyList(), 60)
        val previousLocation = PayloadLocation.fromSighting(previous)
        assertEquals("1581F3YTDJ1D0031Z530", previousLocation.uasId)
        val current = store.ingest(observation(13, 1, OpenDroneIdReferenceFixtures.LOCATION_B, 2_000), emptyList(), 60)
        assertNotEquals(previous.key, current.key)
        assertEquals(1, current.hitCount)
        assertEquals(2_000L, current.firstSeen)
        val location = PayloadLocation.fromSighting(current)
        assertEquals(40.001, location.lat!!, 1e-7)
        assertNull(location.uasId)
        assertNull(location.opLat)
        assertNull(location.opLon)
    }

    private fun observation(generation: Long, peer: Long, message: String, at: Long) = Observation(
        kind = RadioKind.WIFI, mac = WifiNanPeerIdentity.address(receiver, generation, peer),
        name = "Remote ID (Wi-Fi NAN)", rssi = 127, channel = 0, frequencyMhz = 0,
        hiddenSsid = false, serviceUuids = emptyList(), manufacturerId = null,
        manufacturerDataHex = "", rawHex = "", extras = "", at = at,
        facts = RadioFacts(serviceData = listOf(ServiceDataRecord(OpenDroneId.NAN_SERVICE_NAME, "0D$message"))),
    )
}
