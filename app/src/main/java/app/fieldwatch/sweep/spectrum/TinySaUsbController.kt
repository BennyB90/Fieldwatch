package app.fieldwatch.sweep.spectrum

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SpectrumUsbState(
    val devices: List<TinySaDevice> = emptyList(), val selected: TinySaDevice? = null,
    val status: String = "Connect the analyzer by USB, then discover serial devices.",
    val ready: Boolean = false, val detected: TinySaModel? = null, val model: TinySaModel? = null,
    val scanning: Boolean = false, val continuous: Boolean = false, val sweep: SpectrumSweep? = null,
)

internal class TinySaUsbController(private val context: Context) {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val mutable = MutableStateFlow(SpectrumUsbState())
    val state = mutable.asStateFlow()
    private val worker = Executors.newSingleThreadExecutor()
    private val permissionAction = "${context.packageName}.SPECTRUM_USB.${UUID.randomUUID()}"
    private var permission: PendingIntent? = null
    private var foreground = false
    @Volatile private var generation = 0
    private data class Request(val preset: SpectrumPreset, val model: TinySaModel, val threshold: Int)
    private var requests = LinkedBlockingQueue<Request>(1)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!foreground) return
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                if (state.value.selected?.id == device.deviceId) disconnect("Analyzer detached.")
                publish(generation) { it.copy(devices = it.devices.filterNot { d -> d.id == device.deviceId }) }
            } else if (intent.action == permissionAction && permission != null &&
                intent.getIntExtra("generation", -1) == generation && state.value.selected?.id == device.deviceId) {
                permission?.cancel()
                permission = null
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && manager?.hasPermission(device) == true)
                    open(device)
                else disconnect("Android denied access to this USB device.")
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
        try {
            val devices = manager?.deviceList?.values.orEmpty().filter { TinySaUsbCatalog.ports(it) != null }
                .map { TinySaUsbCatalog.describe(it) }.sortedWith(compareByDescending<TinySaDevice> { it.primary }.thenBy { it.id })
            mutable.value = SpectrumUsbState(devices, status = if (devices.isEmpty()) "No compatible USB serial interface found."
                else "Select the serial device attached to your analyzer.")
        } catch (_: Exception) { disconnect("Android could not enumerate USB devices.") }
    }

    fun connect(id: Int) {
        if (!foreground || state.value.devices.none { it.id == id }) return
        disconnect()
        try {
            val usb = manager ?: error("USB host unavailable.")
            val device = usb.deviceList.values.singleOrNull { it.deviceId == id && TinySaUsbCatalog.ports(it) != null }
                ?: error("USB device detached.")
            mutable.value = state.value.copy(selected = TinySaUsbCatalog.describe(device), status = "Requesting USB access…")
            if (usb.hasPermission(device)) open(device) else {
                val intent = Intent(permissionAction).setPackage(context.packageName).putExtra("generation", generation)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val result = PendingIntent.getBroadcast(context, generation, intent, flags)
                permission = result
                usb.requestPermission(device, result)
            }
        } catch (e: Exception) { disconnect(e.message ?: "USB access unavailable.") }
    }

    @Synchronized private fun publish(token: Int, change: (SpectrumUsbState) -> SpectrumUsbState) {
        if (token == generation) mutable.value = change(mutable.value)
    }

    @Synchronized fun disconnect(message: String = "USB disconnected.") {
        generation++
        requests = LinkedBlockingQueue(1)
        permission?.cancel()
        permission = null
        mutable.value = SpectrumUsbState(state.value.devices, status = message)
    }

    fun stop() {
        if (!foreground) return
        foreground = false
        disconnect()
        context.unregisterReceiver(receiver)
        mutable.value = SpectrumUsbState()
    }

    fun close() { stop(); worker.shutdown() }

    fun chooseModel(model: TinySaModel) = publish(generation) {
        if (it.ready && !it.scanning && it.detected == null) it.copy(model = model, sweep = null) else it
    }

    @Synchronized fun scan(preset: SpectrumPreset, continuous: Boolean, threshold: Int) {
        val current = state.value
        val model = current.model ?: return
        if (!foreground || !current.ready || current.scanning || preset.stopHz > model.maxHz) return
        mutable.value = current.copy(scanning = true, continuous = continuous, sweep = null, status = "Sweeping ${preset.title}…")
        requests.add(Request(preset, model, threshold))
    }

    fun finishSweep() = publish(generation) { it.copy(continuous = false) }

    fun current(sweep: SpectrumSweep) = foreground && !sweep.demo && state.value.sweep === sweep

    private fun open(device: UsbDevice) {
        val token = generation
        val queue = requests
        publish(token) { it.copy(status = "Opening serial port and reading version…") }
        worker.execute {
            if (generation != token) return@execute
            try {
                val connection = manager?.openDevice(device) ?: error("Could not open the USB device.")
                try { serve(connection, device, token, queue) } finally { connection.close() }
            } catch (e: Exception) {
                publish(token) { SpectrumUsbState(it.devices, status = e.message ?: "USB serial connection ended.") }
            }
        }
    }

    /** This worker owns the connection, including all interface claims, reads, writes and cleanup. */
    private fun serve(connection: UsbDeviceConnection, device: UsbDevice, token: Int, queue: LinkedBlockingQueue<Request>) {
        val selectedPorts = TinySaUsbCatalog.selection(device) ?: error("Compatible serial interfaces unavailable.")
        if (device.configurationCount > 1 && selectedPorts.configuration != null) {
            check(connection.setConfiguration(selectedPorts.configuration)) { "Could not select the USB serial configuration." }
        }
        val control = selectedPorts.control
        val data = selectedPorts.data
        val input = selectedPorts.input
        val output = selectedPorts.output
        val claimed = mutableListOf<UsbInterface>()
        try {
            for (port in listOf(control, data).distinctBy { it.id }) {
                check(connection.claimInterface(port, true)) { "Could not claim the USB serial interface." }
                claimed.add(port)
            }
            val coding = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0, 0, 0, 8)
            val lineOk = connection.controlTransfer(0x21, 0x20, 0, control.id, coding, coding.size, 500) == coding.size &&
                connection.controlTransfer(0x21, 0x22, 3, control.id, null, 0, 500) == 0
            if (control.interfaceClass == UsbConstants.USB_CLASS_COMM) {
                check(lineOk) { "CDC line setup failed." }
            }
            val buffer = ByteArray(4096)
            fun read(timeout: Int) = connection.bulkTransfer(input, buffer, buffer.size, timeout)
            fun send(bytes: ByteArray) {
                var offset = 0
                while (offset < bytes.size) {
                    check(token == generation) { "USB disconnected." }
                    val n = connection.bulkTransfer(output, bytes, offset, bytes.size - offset, 500)
                    check(n > 0) { "USB serial write failed." }
                    offset += n
                }
            }
            val drainUntil = SystemClock.elapsedRealtime() + 1000
            while (token == generation && read(60) > 0) {
                check(SystemClock.elapsedRealtime() < drainUntil) { "Serial port busy. Stop other output and reconnect." }
            }
            send("\r\nversion\r\n".toByteArray(Charsets.US_ASCII))
            val banner = StringBuilder()
            val versionUntil = SystemClock.elapsedRealtime() + 2200
            while (token == generation && SystemClock.elapsedRealtime() < versionUntil) {
                val n = read(150)
                if (n > 0) {
                    banner.append(String(buffer, 0, n, Charsets.US_ASCII))
                    check(banner.length <= 8192) { "Unexpected serial version response." }
                    if (banner.trimEnd().endsWith("ch>") && TinySaProtocol.parseVersionBanner(banner.toString()) != null) break
                }
            }
            val detected = TinySaProtocol.parseVersionBanner(banner.toString())
            val selected = TinySaUsbCatalog.describe(device, authorized = true)
            publish(token) { it.copy(ready = true, selected = selected, model = detected, detected = detected,
                status = detected?.let { m -> "Connected · ${m.label}" } ?: "Version unrecognized. Select the model printed on your analyzer.") }
            while (token == generation) {
                val request = queue.poll(150, TimeUnit.MILLISECONDS) ?: continue
                try {
                    do {
                        val preset = request.preset
                        send(TinySaProtocol.buildScanRawCommand(preset.startHz, preset.stopHz, preset.defaultPoints, request.model))
                        val decoder = TinySaProtocol.ScanRawDecoder(preset.defaultPoints, request.model)
                        val deadline = SystemClock.elapsedRealtime() + 30_000
                        while (token == generation) {
                            val n = read(200)
                            if (n > 0 && decoder.feed(buffer, n)) break
                            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for a complete scanraw sweep." }
                        }
                        if (token != generation) return
                        val sweep = TinySaProtocol.analyze(decoder.result(), preset, request.model, System.currentTimeMillis(), request.threshold)
                            .copy(deviceLabel = selected.label, manufacturer = selected.manufacturer, serialNumber = selected.serialNumber)
                        var again = false
                        publish(token) {
                            again = it.continuous
                            it.copy(sweep = sweep, scanning = again, status = if (again) "Sweeping ${preset.title}…" else "Sweep complete.")
                        }
                    } while (again && token == generation)
                } catch (rejected: TinySaProtocol.ScanRawRejectedException) {
                    val flushUntil = SystemClock.elapsedRealtime() + 400
                    while (token == generation && SystemClock.elapsedRealtime() < flushUntil && read(50) > 0) Unit
                    publish(token) {
                        it.copy(scanning = false, continuous = false, status = rejected.message ?: TinySaProtocol.REJECTED_MESSAGE)
                    }
                }
            }
        } finally {
            claimed.asReversed().forEach { connection.releaseInterface(it) }
        }
    }
}
