package app.fieldwatch.sweep.spectrum

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import app.fieldwatch.sweep.spaces.SpaceLimits

internal data class TinySaDevice(val id: Int, val label: String, val primary: Boolean,
    val manufacturer: String = "", val serialNumber: String = "")

internal object TinySaUsbCatalog {
    data class Interface(val id: Int, val type: Int, val subclass: Int, val bulkIn: Boolean, val bulkOut: Boolean)
    data class Ports(val control: Int, val data: Int)
    data class EndpointSelection(
        val configuration: UsbConfiguration?,
        val control: UsbInterface,
        val data: UsbInterface,
        val input: UsbEndpoint,
        val output: UsbEndpoint,
    )
    fun primary(vid: Int, pid: Int) = vid == 0x0483 && pid == 0x5740

    fun ports(vid: Int, pid: Int, interfaces: List<Interface>): Ports? {
        if (interfaces.distinctBy { it.id }.size != interfaces.size) return null
        val control = interfaces.filter { it.type == UsbConstants.USB_CLASS_COMM && it.subclass == 2 }
        val data = interfaces.filter { it.type == UsbConstants.USB_CLASS_CDC_DATA && it.bulkIn && it.bulkOut }
        if (control.size == 1 && data.size == 1) return Ports(control.single().id, data.single().id)
        if (!primary(vid, pid) || control.size > 1 || data.size > 1) return null
        val vendor = interfaces.singleOrNull { it.type == UsbConstants.USB_CLASS_VENDOR_SPEC } ?: return null
        val serialData = data.singleOrNull() ?: vendor.takeIf { it.bulkIn && it.bulkOut } ?: return null
        return Ports(control.singleOrNull()?.id ?: vendor.id, serialData.id)
    }

    fun bulk(port: UsbInterface, direction: Int): UsbEndpoint? = (0 until port.endpointCount).map(port::getEndpoint)
        .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == direction }

    private fun resolve(vid: Int, pid: Int, config: UsbConfiguration?, rawInterfaces: List<UsbInterface>): EndpointSelection? {
        val altZero = rawInterfaces.filter { it.alternateSetting == 0 }
        val matched = ports(vid, pid, altZero.map {
            Interface(it.id, it.interfaceClass, it.interfaceSubclass, bulk(it, UsbConstants.USB_DIR_IN) != null,
                bulk(it, UsbConstants.USB_DIR_OUT) != null)
        }) ?: return null
        val control = altZero.singleOrNull { it.id == matched.control } ?: return null
        val data = altZero.singleOrNull { it.id == matched.data } ?: return null
        val input = bulk(data, UsbConstants.USB_DIR_IN) ?: return null
        val output = bulk(data, UsbConstants.USB_DIR_OUT) ?: return null
        return EndpointSelection(config, control, data, input, output)
    }

    fun selection(device: UsbDevice): EndpointSelection? {
        if (device.configurationCount > 0) {
            for (index in 0 until device.configurationCount) {
                val config = device.getConfiguration(index)
                val interfaces = (0 until config.interfaceCount).map(config::getInterface)
                resolve(device.vendorId, device.productId, config, interfaces)?.let { return it }
            }
            return null
        }
        return resolve(device.vendorId, device.productId, null, (0 until device.interfaceCount).map(device::getInterface))
    }

    fun ports(device: UsbDevice) = selection(device)?.let { Ports(it.control.id, it.data.id) }

    fun describe(device: UsbDevice, authorized: Boolean = false): TinySaDevice {
        fun field(read: () -> String?) = SpaceLimits.clean(runCatching(read).getOrNull().orEmpty(), SpaceLimits.RADIO_TEXT)
        return TinySaDevice(device.deviceId, field { device.productName }.ifBlank { "USB serial device ${device.deviceId}" },
            primary(device.vendorId, device.productId), if (authorized) field { device.manufacturerName } else "",
            if (authorized) field { device.serialNumber } else "")
    }
}
