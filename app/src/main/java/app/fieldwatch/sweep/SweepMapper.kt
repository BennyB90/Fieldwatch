package app.fieldwatch.sweep

import app.fieldwatch.domain.Fleet
import app.fieldwatch.domain.MatchRule
import app.fieldwatch.domain.OpenDroneId
import app.fieldwatch.domain.OperatorLoc
import app.fieldwatch.domain.PayloadLocation
import app.fieldwatch.domain.RuleKind
import app.fieldwatch.domain.SignatureClass
import app.fieldwatch.domain.SignatureEngine
import app.fieldwatch.domain.Sighting
import app.fieldwatch.domain.Rssi

/** Consumer interpretation of the upstream catalog; never a physical-device verdict. */
object SweepMapper {
    private val catalogEngine = SignatureEngine()
    private val evidenceEngine = SignatureEngine()
    private var sourceCatalog: List<Fleet>? = null
    private var enabledCatalog: List<Fleet> = emptyList()

    @Synchronized
    fun finding(sighting: Sighting, fleets: List<Fleet>): SweepFinding {
        if (sourceCatalog !== fleets) {
            sourceCatalog = fleets
            enabledCatalog = fleets.filter { it.enabled }
        }
        // Keep upstream product/protocol suppression (for example Apple Device vs AirTag).
        val directIds = catalogEngine.match(listOf(sighting), enabledCatalog, sighting.lastSeen)[sighting.key].orEmpty()
        val matched = enabledCatalog.filter { fleet ->
            fleet.id in directIds || (fleet.isCluster() && fleet.id in sighting.fleetIds)
        }
        val decoded = runCatching { OpenDroneId.fromFacts(sighting.facts) }.getOrDefault(PayloadLocation())
        val remoteIdDecoded = decoded.basicId || !decoded.uasId.isNullOrBlank() ||
            !decoded.selfId.isNullOrBlank() || decoded.pin() != null ||
            PayloadLocation.validCoord(decoded.opLat, decoded.opLon) || decoded.opKind?.let { it in 0..2 } == true
        val category = when {
            remoteIdDecoded || matched.any { it.kind == SignatureClass.DRONE } -> SweepCategory.DRONE
            matched.any { it.kind == SignatureClass.FINDER } -> SweepCategory.TRACKER
            matched.any { it.kind == SignatureClass.CAMERA } -> SweepCategory.CAMERA
            else -> SweepCategory.OTHER
        }
        val evidence = buildList {
            if (matched.isEmpty() && !remoteIdDecoded) add(SweepEvidence("Radio observation", "No catalog signature matched."))
            matched.forEach { fleet ->
                add(SweepEvidence("Matched signature", fleet.name))
                fleet.rules.filter { it.enabled && ruleMatches(sighting, it) }.forEach { rule ->
                    add(ruleEvidence(fleet, rule, sighting))
                }
                if (fleet.isCluster()) {
                    // The caller's store classified this using its peer set. One sighting cannot
                    // independently reconstruct peers or assert that every configured rule hit.
                    add(SweepEvidence("Cluster signature", "Upstream peer-group match; peer details are not in this finding."))
                }
            }
            if (remoteIdDecoded) add(SweepEvidence("Remote ID", "OpenDroneID message decoded from advertised radio data."))
            if (sighting.channel > 0) add(SweepEvidence("Wi-Fi channel", sighting.channel.toString()))
            if (sighting.randomized) add(SweepEvidence("Address behavior", "Randomized address; a changed address may represent the same device."))
            if (Rssi.measured(sighting.rssi)) add(SweepEvidence("Signal strength", "${sighting.rssi} dBm; signal strength is not a distance measurement."))
            else add(SweepEvidence("Signal strength", "Signal strength is unavailable for this observation."))
            if (sighting.facts.serviceData.any { it.uuid == OpenDroneId.NAN_SERVICE_NAME }) {
                add(SweepEvidence("Transport identity", "Wi-Fi NAN discovery uses a session-scoped peer handle; it is not a verified radio address."))
            }
        }.distinct()
        val location = if (category == SweepCategory.DRONE) {
            decoded.mergeSticky(PayloadLocation.fromSighting(sighting))
        } else PayloadLocation()
        val pins = buildList {
            if (PayloadLocation.validCoord(location.lat, location.lon)) {
                add(SweepPin(SweepPinKind.AIRCRAFT, location.lat!!, location.lon!!))
            }
            if (PayloadLocation.validCoord(location.opLat, location.opLon)) {
                val kind = when (location.opKind) {
                    OperatorLoc.TAKEOFF -> SweepPinKind.TAKEOFF
                    OperatorLoc.LIVE -> SweepPinKind.CONTROL_STATION
                    OperatorLoc.FIXED -> SweepPinKind.FIXED_OPERATOR
                    else -> SweepPinKind.UNSPECIFIED_OPERATOR
                }
                add(SweepPin(kind, location.opLat!!, location.opLon!!))
            }
        }
        return SweepFinding(
            id = sighting.key,
            name = sighting.name.takeIf { it.isNotBlank() } ?: matched.firstOrNull()?.name ?: categoryLabel(category),
            category = category,
            transport = if (sighting.facts.serviceData.any { it.uuid == OpenDroneId.NAN_SERVICE_NAME }) "WIFI NAN" else sighting.kind.name,
            rssi = sighting.rssi,
            firstSeenAt = sighting.firstSeen,
            lastSeenAt = sighting.lastSeen,
            observations = sighting.hitCount,
            signatureNames = matched.map { it.name }.distinct(),
            evidence = evidence,
            nextSteps = nextSteps(category),
            identifier = sighting.mac,
            randomizedAddress = sighting.randomized,
            dronePins = pins,
            droneId = location.uasId,
            interpretation = if (category == SweepCategory.DRONE && !remoteIdDecoded) {
                "A drone-related catalog signature matched. No Remote ID message was decoded from the current radio evidence; identity and flight status remain unverified."
            } else interpretation(category),
        )
    }

    private fun Fleet.isCluster(): Boolean = minPeers > 0 || clusterByOui || sequentialMac

    private fun ruleMatches(sighting: Sighting, rule: MatchRule): Boolean {
        val probe = Fleet(id = "sweep-evidence", name = "Rule check", rules = listOf(rule))
        return "sweep-evidence" in evidenceEngine.match(listOf(sighting), listOf(probe), sighting.lastSeen)[sighting.key].orEmpty()
    }

    private fun ruleEvidence(fleet: Fleet, rule: MatchRule, sighting: Sighting): SweepEvidence {
        val signature = fleet.name
        return when (rule.kind) {
            RuleKind.NAME_CONTAINS -> SweepEvidence("Advertised name rule", "$signature: advertised name contains ${rule.text}; observed ${sighting.name}")
            RuleKind.NAME_GLOB -> SweepEvidence("Advertised name rule", "$signature: advertised name matches ${rule.text}; observed ${sighting.name}")
            RuleKind.OUI -> SweepEvidence("Catalog prefix rule", "$signature: ${rule.text} matched address or vendor information")
            RuleKind.MAC_PREFIX -> SweepEvidence("Address prefix rule", "$signature: address matches ${rule.text}")
            RuleKind.VENDOR_IE_OUI -> SweepEvidence("Wi-Fi vendor rule", "$signature: vendor information matches ${rule.text}")
            RuleKind.SERVICE_UUID -> SweepEvidence("Bluetooth service rule", "$signature: advertised service matches ${rule.text}")
            RuleKind.SERVICE_DATA -> SweepEvidence("Bluetooth payload rule", "$signature: service data matched the catalog pattern")
            RuleKind.MANUFACTURER_ID -> SweepEvidence("Manufacturer rule", "$signature: advertised company ID 0x${rule.companyId.toString(16).uppercase().padStart(4, '0')}")
            RuleKind.MANUFACTURER_DATA -> SweepEvidence("Manufacturer payload rule", "$signature: manufacturer data matched the catalog prefix")
            RuleKind.RADIO_KIND -> SweepEvidence("Radio type rule", "$signature: ${sighting.kind.name} radio")
            RuleKind.HIDDEN_SSID -> SweepEvidence("Hidden network rule", "$signature: Wi-Fi network did not advertise its name")
        }
    }

    internal fun categoryLabel(category: SweepCategory): String = when (category) {
        SweepCategory.TRACKER -> "Tracker indicator"
        SweepCategory.CAMERA -> "Camera indicator"
        SweepCategory.DRONE -> "Drone indicator"
        SweepCategory.OTHER -> "Other radio device"
    }

    internal fun interpretation(category: SweepCategory): String = when (category) {
        SweepCategory.TRACKER -> "An item-finder signature matched. This does not establish ownership, that a device is following you, or unwanted tracking."
        SweepCategory.CAMERA -> "A camera-related signature matched. This does not establish a hidden camera, its location, or whether it is recording."
        SweepCategory.DRONE -> "Drone-related radio evidence was observed. Broadcast positions are self-reported and do not verify the aircraft or a person's current location."
        SweepCategory.OTHER -> "An ordinary radio device or another catalog class was observed. Radio data alone cannot identify a hidden microphone or verify what a device is doing."
    }

    internal fun nextSteps(category: SweepCategory): List<String> = when (category) {
        SweepCategory.TRACKER -> listOf("Check the matched signature and repeat the scan to confirm the observation.", "Use your phone's unwanted-tracker alerts and the manufacturer's identification steps.", "If you suspect unwanted tracking, document the finding and seek help from someone you trust.")
        SweepCategory.CAMERA -> listOf("Compare with cameras and Wi-Fi devices you already know are present.", "Inspect the space visually; a radio match cannot locate a lens or prove recording.", "Ask the property owner about an unfamiliar device and record the evidence if concerned.")
        SweepCategory.DRONE -> listOf("Check which pin represents the aircraft, control station, takeoff point, or fixed operator location.", "Treat reported positions as unverified broadcasts; coordinate age may be unknown.", "Observe from a safe location and do not approach or interfere with the aircraft or operator.")
        SweepCategory.OTHER -> listOf("Compare the signature with familiar nearby devices.", "Repeat a scan after moving or turning off a device you own to help identify it.", "A quiet scan cannot rule out silent cameras, microphones, or unsupported radios.")
    }
}
