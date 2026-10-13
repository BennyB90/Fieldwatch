package app.fieldwatch.sweep.radar

enum class RadarConnectionStatus { DISCONNECTED, DISCOVERING, CONNECTING, SUBSCRIBING, CONNECTED, ERROR }
enum class RadarBand { X, K, KA, KU, LASER }
enum class RadarDirection { FRONT, SIDE, REAR }

data class RadarAccessory(val id: String, val name: String, val rssi: Int? = null)

data class RadarDisplay(
    /** One decoded seven-segment character; may be a mode letter rather than a count. */
    val bogeyText: String,
    /** Number of illuminated detector bar-graph LEDs, 0–8. Not a distance measurement. */
    val signalBars: Int,
    val bands: List<RadarBand>,
    val directions: List<RadarDirection>,
    val receivedAt: Long,
    val muted: Boolean = false,
    /** The detector's own active-search status bit. */
    val systemStatus: Boolean = false,
    val legacy: Boolean = false,
)

data class RadarAlert(
    val index: Int,
    val count: Int,
    val frequencyMhz: Int,
    /** Detector-reported unsigned, unitless values, 0–255; not dBm or distance. */
    val frontStrength: Int,
    val rearStrength: Int,
    val bands: List<RadarBand>,
    val directions: List<RadarDirection>,
    val priority: Boolean,
    val receivedAt: Long,
)

data class ValentineRadarState(
    val status: RadarConnectionStatus = RadarConnectionStatus.DISCONNECTED,
    val devices: List<RadarAccessory> = emptyList(),
    val selectedDevice: RadarAccessory? = null,
    val display: RadarDisplay? = null,
    val alerts: List<RadarAlert> = emptyList(),
    val lastPacketAt: Long? = null,
    val error: String? = null,
)
