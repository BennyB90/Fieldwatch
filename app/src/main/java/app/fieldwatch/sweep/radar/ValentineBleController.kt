package app.fieldwatch.sweep.radar

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Explicit-choice, Activity-scoped BLE transport. No background service, persistence,
 * auto-connect, classic Bluetooth, settings, mode, display-control, or mute commands.
 * UUIDs/framing: ValentineResearch/AndroidESPLibrary, SHA in ValentineEspProtocol.
 */
@SuppressLint("MissingPermission") // Every explicit discovery/connect is gated by availability().
class ValentineBleController(context: Context) : Closeable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val _state = MutableStateFlow(ValentineRadarState())
    val state = _state.asStateFlow()
    private val found = LinkedHashMap<String, Pair<RadarAccessory, BluetoothDevice>>()
    private val assembler = ValentineAlertTableAssembler()
    private var generation = 0L
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var requestCharacteristic: BluetoothGattCharacteristic? = null
    private var notificationsReady = false
    private var alertRequestSent = false
    private var pendingAlertRequest: ByteArray? = null
    private var lastDisplayElapsed = 0L
    private var lastAlertTableElapsed = 0L
    private var lastAlertRequestElapsed = 0L
    private var displayChecksumMode: Boolean? = null

    fun discover() = onMain {
        if (closed.get()) return@onMain
        val unavailable = availability(discovery = true)
        if (unavailable != null) { fail(unavailable); return@onMain }
        generation++
        cleanUp()
        found.clear()
        _state.value = ValentineRadarState(status = RadarConnectionStatus.DISCOVERING)
        val token = generation
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onMain {
                if (!current(token) || _state.value.status != RadarConnectionStatus.DISCOVERING) return@onMain
                if (result.scanRecord?.serviceUuids?.any { it.uuid == ValentineEspProtocol.SERVICE_UUID } != true) return@onMain
                runCatching {
                    val id = result.device.address
                    if (id !in found && found.size >= MAX_DEVICES) return@runCatching
                    val name = result.scanRecord?.deviceName?.trim()?.take(80)?.takeIf { it.isNotBlank() } ?: "Valentine BLE accessory"
                    found[id] = RadarAccessory(id, name, result.rssi) to result.device
                    _state.update { it.copy(devices = found.values.map { entry -> entry.first }.sortedByDescending { device -> device.rssi ?: -128 }) }
                }.onFailure { fail("Bluetooth permission changed. Grant nearby-device access and try again.") }
            }

            override fun onScanFailed(errorCode: Int) = onMain {
                if (current(token)) fail("Accessory discovery did not start. Check Bluetooth and try again.")
            }
        }
        scanCallback = callback
        runCatching {
            scanner = adapter()?.bluetoothLeScanner ?: throw IllegalStateException("No BLE scanner")
            scanner!!.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(ValentineEspProtocol.SERVICE_UUID)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(), callback)
            main.postDelayed({
                if (current(token) && _state.value.status == RadarConnectionStatus.DISCOVERING) {
                    stopDiscovery()
                    _state.update { it.copy(status = RadarConnectionStatus.DISCONNECTED,
                        error = if (it.devices.isEmpty()) "No compatible accessory was found in this scan. Check detector power and try again." else null) }
                }
            }, DISCOVERY_MS)
        }.onFailure { fail("Accessory discovery is unavailable. Check Bluetooth and app permissions.") }
    }

    /** The ID must be one of the devices discovered in this controller, then explicitly chosen. */
    fun connect(accessoryId: String) = onMain {
        if (closed.get()) return@onMain
        val unavailable = availability(discovery = false)
        if (unavailable != null) { fail(unavailable); return@onMain }
        val chosen = found[accessoryId]
        if (chosen == null) { fail("Discover accessories and choose one before connecting."); return@onMain }
        generation++
        cleanUp()
        _state.value = ValentineRadarState(status = RadarConnectionStatus.CONNECTING,
            devices = found.values.map { it.first }, selectedDevice = chosen.first)
        val token = generation
        runCatching {
            gatt = chosen.second.connectGatt(context, false, callback(token), BluetoothDevice.TRANSPORT_LE)
                ?: throw IllegalStateException("No GATT connection")
            main.postDelayed({
                if (current(token) && _state.value.status in listOf(RadarConnectionStatus.CONNECTING, RadarConnectionStatus.SUBSCRIBING)) {
                    fail("The accessory did not connect in time. Close other detector apps and try again.")
                }
            }, CONNECTION_MS)
        }.onFailure { fail("Bluetooth could not connect. Check nearby-device permission and try again.") }
    }

    fun disconnect() = onMain {
        if (closed.get()) return@onMain
        generation++
        cleanUp()
        _state.update { it.copy(status = RadarConnectionStatus.DISCONNECTED, selectedDevice = null,
            display = null, alerts = emptyList(), lastPacketAt = null, error = null) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        onMain {
            generation++
            cleanUp()
            main.removeCallbacksAndMessages(null)
            found.clear()
            _state.value = ValentineRadarState()
        }
    }

    @SuppressLint("MissingPermission")
    private fun callback(token: Long) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) = onMain {
            if (!current(token) || gatt !== connection) return@onMain
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail("The accessory disconnected. Check power and close other detector apps before reconnecting.")
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                _state.update { it.copy(status = RadarConnectionStatus.SUBSCRIBING) }
                if (!runCatching { connection.discoverServices() }.getOrDefault(false)) fail("Accessory services could not be read.")
            }
        }

        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) = onMain {
            if (!current(token) || gatt !== connection) return@onMain
            val service = if (status == BluetoothGatt.GATT_SUCCESS) connection.getService(ValentineEspProtocol.SERVICE_UUID) else null
            val receive = service?.getCharacteristic(ValentineEspProtocol.NOTIFY_UUID)
            val descriptor = receive?.getDescriptor(ValentineEspProtocol.CCCD_UUID)
            if (receive == null || descriptor == null || receive.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
                fail("This accessory does not expose the supported Valentine ESP notification service."); return@onMain
            }
            requestCharacteristic = service?.getCharacteristic(ValentineEspProtocol.REQUEST_UUID)
            val subscribed = runCatching {
                if (!connection.setCharacteristicNotification(receive, true)) return@runCatching false
                if (Build.VERSION.SDK_INT >= 33) connection.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    connection.writeDescriptor(descriptor)
                }
            }.getOrDefault(false)
            if (!subscribed) fail("Detector notifications could not be enabled. Check permissions and reconnect.")
        }

        override fun onDescriptorWrite(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = onMain {
            if (!current(token) || gatt !== connection || descriptor.uuid != ValentineEspProtocol.CCCD_UUID) return@onMain
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("The accessory did not accept display notifications."); return@onMain }
            notificationsReady = true
            lastDisplayElapsed = SystemClock.elapsedRealtime()
            _state.update { it.copy(status = RadarConnectionStatus.CONNECTED, error = null) }
            requestAlertsIfReady()
            checkFreshData(token)
        }

        @Deprecated("Legacy Android callback")
        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value?.copyOf() ?: return
            receive(token, connection, characteristic, bytes)
        }

        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            receive(token, connection, characteristic, value.copyOf())
        }

        override fun onCharacteristicWrite(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = onMain {
            if (current(token) && gatt === connection && characteristic.uuid == ValentineEspProtocol.REQUEST_UUID && status != BluetoothGatt.GATT_SUCCESS) {
                _state.update { it.copy(error = "Display data is connected, but the detailed alert request was not accepted.") }
            }
        }
    }

    private fun receive(token: Long, connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) = onMain {
        if (!current(token) || gatt !== connection || characteristic.uuid != ValentineEspProtocol.NOTIFY_UUID) return@onMain
        val packet = ValentineEspProtocol.decode(bytes)
            ?: displayChecksumMode?.let { ValentineEspProtocol.decodeControl(bytes, it) }
            ?: return@onMain
        val now = System.currentTimeMillis()
        _state.update { it.copy(lastPacketAt = now) }
        ValentineEspProtocol.display(packet, now)?.let { display ->
            displayChecksumMode = packet.withChecksum
            lastDisplayElapsed = SystemClock.elapsedRealtime()
            _state.update { it.copy(display = display) }
            if (display.legacy) _state.update { it.copy(error = "Legacy detector display only. Detailed alert data requires an ESP-capable detector.") }
            if (!display.legacy && display.systemStatus && packet.payload[5] and 0x02 == 0 && !alertRequestSent) {
                pendingAlertRequest = ValentineEspProtocol.startAlertRequest(packet.withChecksum)
                requestAlertsIfReady()
            }
        }
        ValentineEspProtocol.alert(packet, now)?.let { alert ->
            assembler.accept(alert, SystemClock.elapsedRealtime())?.let { alerts ->
                lastAlertTableElapsed = SystemClock.elapsedRealtime()
                _state.update { it.copy(alerts = alerts, error = it.error.takeUnless { message -> message == STALE_ALERT_MESSAGE }) }
            }
        }
        if (packet.packetId == 0x64 && packet.payload.firstOrNull() == 0x41) {
            _state.update { it.copy(error = "This detector does not support the detailed alert stream. Display data remains available.") }
        }
        if (packet.packetId in listOf(0x65, 0x67) && packet.payload.firstOrNull() == 0x41) {
            _state.update { it.copy(error = "The detector declined the detailed alert request. Display data remains available.") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestAlertsIfReady() {
        val bytes = pendingAlertRequest ?: return
        val connection = gatt ?: return
        if (!notificationsReady || alertRequestSent) return
        alertRequestSent = true
        val target = requestCharacteristic
        if (target == null || target.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
            _state.update { it.copy(error = "Display data is connected. This accessory does not offer detailed alert requests.") }
            return
        }
        val type = if (target.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val accepted = runCatching {
            if (Build.VERSION.SDK_INT >= 33) connection.writeCharacteristic(target, bytes, type) == BluetoothStatusCodes.SUCCESS
            else {
                target.writeType = type
                @Suppress("DEPRECATION")
                target.value = bytes
                @Suppress("DEPRECATION")
                connection.writeCharacteristic(target)
            }
        }.getOrDefault(false)
        if (accepted) lastAlertRequestElapsed = SystemClock.elapsedRealtime()
        if (!accepted) _state.update { it.copy(error = "Display data is connected, but the detailed alert request could not be sent.") }
    }

    private fun checkFreshData(token: Long) {
        main.postDelayed({
            if (current(token) && _state.value.status == RadarConnectionStatus.CONNECTED) {
                if (SystemClock.elapsedRealtime() - lastDisplayElapsed >= DATA_TIMEOUT_MS) {
                    fail("No fresh supported detector display data is arriving. Reconnect and check hardware compatibility.")
                } else {
                    if (lastAlertRequestElapsed > 0 && lastAlertTableElapsed == 0L && SystemClock.elapsedRealtime() - lastAlertRequestElapsed > 6_000L) {
                        _state.update { it.copy(error = it.error ?: "Detailed alert data has not arrived. Only detector display data is available.") }
                    }
                    if (lastAlertTableElapsed > 0 && SystemClock.elapsedRealtime() - lastAlertTableElapsed > 4_000L && _state.value.alerts.isNotEmpty()) {
                        _state.update { it.copy(alerts = emptyList(), error = STALE_ALERT_MESSAGE) }
                    }
                    checkFreshData(token)
                }
            }
        }, 2_000L)
    }

    private fun current(token: Long) = !closed.get() && generation == token
    private fun adapter() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private fun permitted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun availability(discovery: Boolean): String? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return "Bluetooth LE is not supported by this phone."
        if (Build.VERSION.SDK_INT >= 31 && (!permitted(Manifest.permission.BLUETOOTH_CONNECT) || discovery && !permitted(Manifest.permission.BLUETOOTH_SCAN))) {
            return "Grant nearby-device permission to discover and connect an accessory."
        }
        // The shared RF app intentionally does not assert neverForLocation, which would
        // filter some BLE beacons. Keep discovery consistent with its full-scan permission.
        if (discovery) {
            if (!permitted(Manifest.permission.ACCESS_FINE_LOCATION)) return "Android requires location permission for Bluetooth discovery."
            if ((context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isLocationEnabled != true) return "Turn on Android Location for Bluetooth discovery."
        }
        if (runCatching { adapter()?.isEnabled == true }.getOrDefault(false).not()) return "Bluetooth is off or unavailable. Turn it on in Android Settings."
        return null
    }

    private fun fail(message: String) {
        generation++
        cleanUp()
        _state.update { it.copy(status = RadarConnectionStatus.ERROR, display = null, alerts = emptyList(), lastPacketAt = null, error = message) }
    }

    @SuppressLint("MissingPermission")
    private fun stopDiscovery() {
        val activeScanner = scanner
        val callback = scanCallback
        scanner = null; scanCallback = null
        if (activeScanner != null && callback != null) runCatching { activeScanner.stopScan(callback) }
    }

    @SuppressLint("MissingPermission")
    private fun cleanUp() {
        stopDiscovery()
        val old = gatt
        gatt = null
        if (old != null) { runCatching { old.disconnect() }; runCatching { old.close() } }
        requestCharacteristic = null; notificationsReady = false; alertRequestSent = false
        pendingAlertRequest = null; lastDisplayElapsed = 0L; lastAlertTableElapsed = 0L; lastAlertRequestElapsed = 0L; assembler.reset()
        displayChecksumMode = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private companion object {
        const val MAX_DEVICES = 32
        const val DISCOVERY_MS = 15_000L
        const val CONNECTION_MS = 20_000L
        const val DATA_TIMEOUT_MS = 12_000L
        const val STALE_ALERT_MESSAGE = "Detailed alert data is stale. The detector display may still be connected."
    }
}
