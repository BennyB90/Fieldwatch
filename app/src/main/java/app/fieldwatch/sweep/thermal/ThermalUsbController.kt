package app.fieldwatch.sweep.thermal

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.*
import android.os.Build
import android.os.SystemClock
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ThermalCapture(val bytes: ByteArray, val capturedAt: Long, val elapsedAt: Long, val generation: Int)
internal data class ThermalUsbState(
    val devices: List<ThermalDevice> = emptyList(),
    val status: String = "Discover a connected USB thermal accessory.",
    val selected: ThermalDevice? = null,
    val capture: ThermalCapture? = null,
    val skipped: Long = 0,
    val dropped: Long = 0,
    val corrupt: Long = 0,
)

@Keep
internal interface UvcSink {
    fun stopped(): Boolean
    fun onFrame(bytes: ByteArray)
    fun onLoss(dropped: Long, corrupt: Long)
}

@Keep
internal object UvcNative {
    init { System.loadLibrary("sweep_uvc") }
    external fun run(fd: Int, interfaceNumber: Int, altSetting: Int, endpointAddress: Int,
        maxPacketSize: Int, packetsPerUrb: Int, numUrbs: Int, targetFrameBytes: Int, sink: UvcSink): Int
}

/** UI commands arrive on the main thread. One worker owns the fd from open through release/close. */
internal class ThermalUsbController(private val context: Context) {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val mutable = MutableStateFlow(ThermalUsbState())
    val state = mutable.asStateFlow()
    private val executor = Executors.newSingleThreadExecutor()
    private val permissionAction = "${context.packageName}.THERMAL_USB.${UUID.randomUUID()}"
    @Volatile private var generation = 0
    private var foreground = false
    private var permission: PendingIntent? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!foreground) return
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                if (state.value.selected?.id == device.deviceId) disconnect("USB accessory detached.")
                publish(generation) { it.copy(devices = it.devices.filterNot { entry -> entry.id == device.deviceId }) }
            } else if (intent.action == permissionAction && permission != null &&
                intent.getIntExtra("generation", -1) == generation && state.value.selected?.id == device.deviceId) {
                permission?.cancel()
                permission = null
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && manager?.hasPermission(device) == true)
                    stream(device, generation)
                else disconnect(DENIED)
            }
        }
    }

    fun start() {
        if (foreground) return
        ContextCompat.registerReceiver(context, receiver, IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        foreground = true
    }

    fun discover() {
        if (!foreground) return
        disconnect()
        runCatching {
            val devices = manager?.let(ThermalUsbCatalog::candidates).orEmpty().map { ThermalUsbCatalog.describe(it) }
            mutable.value = ThermalUsbState(devices, if (devices.isEmpty()) "No USB video interfaces found."
                else "Select your Tiny1-C / P2 thermal accessory.")
        }.onFailure { disconnect("Android could not enumerate USB accessories.") }
    }

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun connect(id: Int) {
        if (!foreground || state.value.devices.none { it.id == id }) return
        disconnect()
        val token = generation
        try {
            check(hasCameraPermission()) { CAMERA_REQUIRED }
            val usb = manager ?: error("USB host unavailable.")
            val device = ThermalUsbCatalog.candidates(usb).find { it.deviceId == id } ?: error("Accessory detached.")
            mutable.value = state.value.copy(selected = ThermalUsbCatalog.describe(device), status = "Requesting USB access…")
            if (usb.hasPermission(device)) stream(device, token)
            else {
                val intent = Intent(permissionAction).setPackage(context.packageName).putExtra("generation", token)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val result = PendingIntent.getBroadcast(context, token, intent, flags)
                permission = result
                usb.requestPermission(device, result)
            }
        } catch (_: SecurityException) { disconnect(DENIED) }
        catch (e: Exception) { disconnect(e.message ?: "Could not connect to the accessory.") }
    }

    @Synchronized
    private fun publish(token: Int, change: (ThermalUsbState) -> ThermalUsbState) {
        if (generation == token) mutable.value = change(mutable.value)
    }

    @Synchronized
    fun disconnect(message: String = "USB disconnected.") {
        generation++
        permission?.cancel()
        permission = null
        mutable.value = ThermalUsbState(devices = state.value.devices, status = message)
    }

    fun stop() {
        if (!foreground) return
        foreground = false
        disconnect()
        context.unregisterReceiver(receiver)
        mutable.value = ThermalUsbState()
    }

    fun close() { stop(); executor.shutdown() }

    fun currentDevice(capture: ThermalCapture): ThermalDevice? {
        val current = state.value
        return current.selected?.takeIf {
            foreground && current.capture?.generation == capture.generation &&
                SystemClock.elapsedRealtime() - capture.elapsedAt in 0..1500
        }
    }

    private fun stream(device: UsbDevice, token: Int) {
        publish(token) { it.copy(status = "Reading UVC streaming modes…") }
        executor.execute {
            if (token != generation) return@execute
            try {
                val connection = manager?.openDevice(device) ?: error(DENIED)
                try { receive(connection, device, token) } finally { connection.close() }
            } catch (e: Exception) {
                publish(token) { it.copy(selected = null, capture = null, status = e.message ?: "USB stream ended.") }
            } catch (_: UnsatisfiedLinkError) {
                publish(token) { it.copy(selected = null, capture = null, status = "USB isochronous bridge unavailable.") }
            }
        }
    }

    private fun receive(connection: UsbDeviceConnection, device: UsbDevice, token: Int) {
        val selected = ThermalUsbCatalog.describe(device, authorized = true)
        publish(token) { it.copy(selected = selected) }
        val catalog = UvcProtocol.parse(connection.rawDescriptors)
        val (stream, preferred) = catalog.thermal() ?: error("No supported 256 × 384 or 256 × 192 YUY2 thermal mode.")
        val config = (0 until device.configurationCount).map(device::getConfiguration)
            .firstOrNull { it.id == stream.configuration } ?: error("UVC configuration descriptor unavailable.")
        if (device.configurationCount > 1) check(connection.setConfiguration(config)) { "Could not select the UVC configuration." }
        val allInterfaces = (0 until config.interfaceCount).map(config::getInterface)
        val interfaces = allInterfaces.filter { it.id == stream.number }
        val idle = interfaces.firstOrNull { it.alternateSetting == 0 }
            ?: error("UVC streaming idle interface unavailable.")
        val control = stream.controlInterface?.takeIf { it != idle.id }?.let { vcId ->
            allInterfaces.firstOrNull { it.id == vcId && it.alternateSetting == 0 } ?: allInterfaces.firstOrNull { it.id == vcId }
        }
        if (control != null) check(connection.claimInterface(control, true)) { "Android could not claim the UVC control interface." }
        try {
            check(connection.claimInterface(idle, true)) { "Android could not claim the UVC streaming interface." }
            try {
                if (token != generation) return
                val (mode, negotiated) = UvcProtocol.negotiate(stream, preferred) { direction, code, selector, bytes ->
                    check(token == generation) { "USB disconnected." }
                    connection.controlTransfer(direction, code, selector shl 8, stream.number, bytes, bytes.size, 600)
                }
                val endpoint = stream.endpoint(negotiated.payloadBytes) ?: error("No advertised endpoint can carry the negotiated payload.")
                val sink = object : UvcSink {
                    override fun stopped() = token != generation
                    override fun onLoss(dropped: Long, corrupt: Long) = publish(token) { it.copy(dropped = dropped, corrupt = corrupt) }
                    override fun onFrame(bytes: ByteArray) {
                        if (ThermalFrameParser.isShutter(bytes)) publish(token) { it.copy(skipped = it.skipped + 1) }
                        else publish(token) { it.copy(capture = ThermalCapture(bytes, System.currentTimeMillis(), SystemClock.elapsedRealtime(), token)) }
                    }
                }
                if (sink.stopped()) return
                val extra = if (catalog.secondaryVisible(stream)) " · secondary higher-resolution stream available" else ""
                publish(token) { it.copy(status = "USB ${endpoint.transport.name.lowercase()} · ${mode.width} × ${mode.height}$extra") }
                if (endpoint.transport == UvcTransport.ISOCHRONOUS) {
                    val rc = UvcNative.run(connection.fileDescriptor, stream.number, endpoint.alt, endpoint.address,
                        endpoint.wMaxPacketSize, 32, 8, mode.frameBytes, sink)
                    check(rc >= 0) { "USB isochronous stream ended (errno ${-rc})." }
                } else {
                    val active = interfaces.firstOrNull { it.alternateSetting == endpoint.alt }
                        ?: error("UVC bulk alternate interface unavailable.")
                    if (endpoint.alt != 0) check(connection.setInterface(active)) { "Could not select the bulk alternate setting." }
                    val input = (0 until active.endpointCount).map(active::getEndpoint)
                        .firstOrNull { it.address == endpoint.address } ?: error("UVC bulk endpoint unavailable.")
                    val frames = UvcReassembler(mode.frameBytes, sink::onFrame)
                    val accumulator = UvcBulkAccumulator(negotiated.payloadBytes.toInt()) { payload, total ->
                        frames.packet(payload, total)
                        if (frames.dropped != state.value.dropped) sink.onLoss(frames.dropped, frames.corruptFrames)
                    }
                    var lastPayload = SystemClock.elapsedRealtime()
                    while (!sink.stopped()) {
                        val length = connection.bulkTransfer(input, accumulator.buffer, accumulator.offset, accumulator.nextReadSize(), 200)
                        if (length >= 0) {
                            if (length > 0 || accumulator.offset > 0) lastPayload = SystemClock.elapsedRealtime()
                            accumulator.onTransfer(length)
                        } else {
                            accumulator.onTransfer(length)
                            check(SystemClock.elapsedRealtime() - lastPayload < 3000) { "USB bulk stream stopped delivering payloads." }
                        }
                    }
                }
            } finally {
                // Native resets its own alternate setting; this also resets bulk alternates on early failures.
                try { connection.setInterface(idle) } finally { connection.releaseInterface(idle) }
            }
        } finally {
            if (control != null) connection.releaseInterface(control)
        }
    }

    internal companion object {
        const val CAMERA_REQUIRED = "Camera permission is required so Android allows USB video access to this thermal accessory. SWEEP uses USB only and never opens the phone's built-in cameras."
        const val DENIED = "Android denied USB video access. Confirm Camera permission is allowed in Settings and accept the USB prompt."
    }
}
