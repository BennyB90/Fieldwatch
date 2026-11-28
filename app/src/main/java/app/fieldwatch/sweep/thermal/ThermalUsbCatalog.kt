package app.fieldwatch.sweep.thermal

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

internal data class ThermalDevice(val id: Int, val label: String, val manufacturer: String = "", val serialNumber: String = "")

internal object ThermalUsbCatalog {
    fun candidates(manager: UsbManager) = manager.deviceList.values.filter { device ->
        hasVideo(device.deviceClass, (0 until device.interfaceCount).map { device.getInterface(it).interfaceClass })
    }.sortedBy { it.deviceId }

    fun hasVideo(deviceClass: Int, interfaceClasses: List<Int>) = deviceClass == 14 || 14 in interfaceClasses

    fun describe(device: UsbDevice, authorized: Boolean = false): ThermalDevice {
        val product = runCatching { device.productName }.getOrNull()
        val label = product?.trim()?.take(100)?.takeIf { it.isNotEmpty() } ?: "USB video device ${device.deviceId}"
        return if (authorized) ThermalDevice(device.deviceId, label,
            runCatching { device.manufacturerName }.getOrNull().orEmpty(),
            runCatching { device.serialNumber }.getOrNull().orEmpty())
        else ThermalDevice(device.deviceId, label)
    }
}
