package app.fieldwatch.sweep

import kotlinx.serialization.Serializable

@Serializable
enum class SweepCategory { TRACKER, CAMERA, DRONE, OTHER }

@Serializable
enum class SweepPinKind { AIRCRAFT, CONTROL_STATION, TAKEOFF, FIXED_OPERATOR, UNSPECIFIED_OPERATOR }

@Serializable
data class SweepPin(
    val kind: SweepPinKind,
    val latitude: Double,
    val longitude: Double,
    /** Actual decoding time, when known. Never substitute the latest radio observation. */
    val observedAt: Long? = null,
)

@Serializable
data class SweepEvidence(val label: String, val value: String)

@Serializable
data class SweepFinding(
    val id: String,
    val name: String,
    val category: SweepCategory,
    val transport: String,
    val rssi: Int,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val observations: Int,
    val signatureNames: List<String> = emptyList(),
    val evidence: List<SweepEvidence> = emptyList(),
    val nextSteps: List<String> = emptyList(),
    val identifier: String = "",
    val randomizedAddress: Boolean = false,
    val dronePins: List<SweepPin> = emptyList(),
    val droneId: String? = null,
    val interpretation: String = "Radio observation; physical identity has not been verified.",
)

@Serializable
data class SweepCoverage(
    val wifiSupported: Boolean = false,
    val bleSupported: Boolean = false,
    val wifiReady: Boolean = false,
    val bleReady: Boolean = false,
    val wifiStatus: String = "Not checked",
    val bleStatus: String = "Not checked",
    val wifiSamples: Long = 0,
    val bleSamples: Long = 0,
    val droppedSamples: Long = 0,
    val wifiLastSampleAt: Long? = null,
    val wifiIeAvailable: Boolean = false,
    val bleExtendedSupported: Boolean? = null,
    val wifiNanAvailable: Boolean = false,
)

@Serializable
enum class SweepSessionState { ACTIVE, COMPLETE, INTERRUPTED }

@Serializable
data class SweepSession(
    val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val state: SweepSessionState = SweepSessionState.ACTIVE,
    val findings: List<SweepFinding> = emptyList(),
    val coverage: SweepCoverage = SweepCoverage(),
    val phone: String = "",
    val androidVersion: String = "",
    val catalogVersion: Int = 95,
    val startBattery: Int? = null,
    val endBattery: Int? = null,
    val error: String? = null,
    val demo: Boolean = false,
)
