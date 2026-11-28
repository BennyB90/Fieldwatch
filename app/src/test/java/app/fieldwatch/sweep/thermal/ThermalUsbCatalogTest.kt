package app.fieldwatch.sweep.thermal

import org.junit.Assert.*
import org.junit.Test

class ThermalUsbCatalogTest {
    @Test fun listsVideoDevicesAndCompositeDevicesWithAVideoInterface() {
        assertTrue(ThermalUsbCatalog.hasVideo(14, emptyList()))
        assertTrue(ThermalUsbCatalog.hasVideo(0, listOf(1, 14)))
        assertTrue(ThermalUsbCatalog.hasVideo(239, listOf(14, 3)))
        assertFalse(ThermalUsbCatalog.hasVideo(239, listOf(2, 10)))
        assertFalse(ThermalUsbCatalog.hasVideo(3, listOf(3)))
        assertFalse(ThermalUsbCatalog.hasVideo(0, emptyList()))
    }
}
