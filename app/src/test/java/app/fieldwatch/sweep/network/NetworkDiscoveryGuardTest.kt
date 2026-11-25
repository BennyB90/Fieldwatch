package app.fieldwatch.sweep.network

import org.junit.Assert.*
import org.junit.Test

class NetworkDiscoveryGuardTest {
    private fun source(network: String? = "wifiA", wifi: Boolean = true, vpn: Boolean = false,
        permitted: Boolean = true, iface: String? = "wlan0") = NetworkDiscoverySource(network, wifi, vpn, permitted, iface)

    @Test fun legacyAndroidNeverPretendsToScopeServices() {
        for (sdk in 29..32) assertFalse(NetworkDiscoveryGuard.supported(sdk))
        for (sdk in 33..37) assertTrue(NetworkDiscoveryGuard.supported(sdk))
    }

    @Test fun unknownAndOtherNetworkServiceSourcesAreRejected() {
        val gate = NetworkDiscoveryGuard()
        val token = gate.begin("wifiA", "wlan0")
        assertTrue(gate.acceptsService(token, source(), "wifiA"))
        assertFalse(gate.acceptsService(token, source(), null))
        assertFalse(gate.acceptsService(token, source(), "wifiB"))
        assertFalse(gate.acceptsService(token, source("wifiB"), "wifiA"))
    }

    @Test fun vpnPermissionLossInterfaceChangeAndNonWifiFenceEveryResult() {
        val gate = NetworkDiscoveryGuard()
        val token = gate.begin("wifiA", "wlan0")
        val invalid = listOf(source(vpn = true), source(permitted = false), source(wifi = false),
            source(iface = "wlan1"), source(iface = null), source(network = null))
        invalid.forEach { assertFalse(gate.acceptsService(token, it, "wifiA")) }
    }

    @Test fun lateResolveOrDiscoveryCannotCrossStopAndRestartEvenOnSameWifi() {
        val gate = NetworkDiscoveryGuard()
        val old = gate.begin("wifiA", "wlan0")
        gate.cancel()
        assertFalse(gate.acceptsService(old, source(), "wifiA"))
        val current = gate.begin("wifiA", "wlan0")
        assertFalse(gate.acceptsService(old, source(), "wifiA"))
        assertTrue(gate.acceptsService(current, source(), "wifiA"))
    }

    @Test fun explicitTypesAndBoundedNamesDoNotAcceptArbitraryQueries() {
        assertEquals(8, NetworkDiscoveryTypes.requested.distinct().size)
        assertEquals("_http._tcp.", NetworkDiscoveryTypes.canonical("_HTTP._TCP"))
        // Android's resolved-service fixture prepends one dot, unlike discovery callbacks.
        assertEquals("_http._tcp.", NetworkDiscoveryTypes.canonical("._http._tcp"))
        assertEquals("_http._tcp.", NetworkDiscoveryTypes.canonical("._http._tcp."))
        assertNull(NetworkDiscoveryTypes.canonical(".._http._tcp."))
        assertNull(NetworkDiscoveryTypes.canonical("_http._tcp.."))
        assertNull(NetworkDiscoveryTypes.canonical("_services._dns-sd._udp."))
        assertNull(NetworkDiscoveryTypes.canonical("_http._tcp.local."))
        assertFalse(NetworkDiscoveryTypes.validName(""))
        assertFalse(NetworkDiscoveryTypes.validName("bad\nname"))
        assertFalse(NetworkDiscoveryTypes.validName("x".repeat(129)))
        assertTrue(NetworkDiscoveryTypes.validName("Office printer"))
    }

    @Test fun serviceKeysDoNotMergeTypesNamesOrSeparateReceiverSessions() {
        val key = NetworkDiscoveryTypes.key("session1", "_http._tcp.", "device")
        assertEquals(key, NetworkDiscoveryTypes.key("session1", "_http._tcp.", "device"))
        assertNotEquals(key, NetworkDiscoveryTypes.key("session2", "_http._tcp.", "device"))
        assertNotEquals(key, NetworkDiscoveryTypes.key("session1", "_https._tcp.", "device"))
        assertNotEquals(key, NetworkDiscoveryTypes.key("session1", "_http._tcp.", "device2"))
    }

    @Test fun invisibleAndBidiFormatCharactersCannotBreakSavedHistoryValidation() {
        for (character in listOf('\u200B', '\u200D', '\u202E', '\u2066', '\uFEFF')) {
            assertFalse("format ${character.code}", NetworkDiscoveryTypes.validName("Office${character}printer"))
        }
        assertTrue(NetworkDiscoveryTypes.validName("Imprimante Étage 2"))
    }
}
