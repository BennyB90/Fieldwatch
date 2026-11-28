package app.fieldwatch.sweep.spectrum

import app.fieldwatch.sweep.spectrum.TinySaUsbCatalog.Interface
import app.fieldwatch.sweep.spectrum.TinySaUsbCatalog.Ports
import org.junit.Assert.*
import org.junit.Test

class TinySaUsbCatalogTest {
    private val control = Interface(3, 2, 2, false, false)
    private val data = Interface(4, 10, 0, true, true)
    private val vendor = Interface(1, 255, 0, true, true)

    @Test fun distinguishesKnownStm32FromGenericAcmAndRestrictsVendorFallback() {
        assertTrue(TinySaUsbCatalog.primary(0x0483, 0x5740))
        assertFalse(TinySaUsbCatalog.primary(0x0483, 0x5741))
        assertEquals(Ports(3, 4), TinySaUsbCatalog.ports(1, 2, listOf(data, control)))
        assertEquals(Ports(1, 1), TinySaUsbCatalog.ports(0x0483, 0x5740, listOf(vendor)))
        assertEquals(Ports(3, 1), TinySaUsbCatalog.ports(0x0483, 0x5740, listOf(control, vendor)))
        assertEquals(Ports(1, 4), TinySaUsbCatalog.ports(0x0483, 0x5740, listOf(vendor.copy(bulkOut = false), data)))
        assertNull(TinySaUsbCatalog.ports(0x0483, 0x5741, listOf(vendor)))
    }

    @Test fun rejectsMissingEndpointsNonAcmAndAmbiguousCompositeDevices() {
        for (interfaces in listOf(listOf(control), listOf(data), listOf(control, data.copy(bulkIn = false)),
                listOf(control, data.copy(bulkOut = false)), listOf(control.copy(subclass = 6), data),
                listOf(control, control.copy(id = 5), data), listOf(data.copy(type = 14)),
                listOf(control, data, Interface(3, 8, 6, true, true)))) {
            assertNull(TinySaUsbCatalog.ports(0x0483, 0x5740, interfaces))
        }
    }
}
