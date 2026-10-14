# SWEEP network discovery — Alpha 3

Updated October 13, 2026. Implemented in **SWEEP + Radio** (`sweepRadio`, installation ID `app.fieldwatch.sweep.radio`) only. The offline `sweep` build retains its no-Internet-permission boundary and has no Network entry. This feature reviews services advertised on an authorized Wi-Fi network and generic router inventories the user imports. It does not classify an endpoint as a recorder.

## Live discovery

Open **Network**, connect to a Wi-Fi network you own, manage or have permission to inspect, and choose **Start 60-second discovery**. The confirmation explains the network requests and requires an authorization checkbox. End any room RF scan, Travel mode or radar accessory connection first; discovery shares their exclusive scan ownership.

Live discovery requires **Android 13 / API 33 or later**. SWEEP passes the captured current default Wi-Fi `Network` to Android's network-scoped DNS-SD browsing API and accepts discovery/resolution callbacks only when they identify that same network. Earlier Android versions are deliberately unsupported for live discovery; Android 10+ still supports saved snapshots and authorized inventory imports. The network-scoped overload and result-network field were added in API 33. [Android NsdManager reference](https://developer.android.com/reference/android/net/nsd/NsdManager), [NsdServiceInfo.getNetwork](https://developer.android.com/reference/android/net/nsd/NsdServiceInfo#getNetwork()).

An active VPN prevents discovery. Changing the default Wi-Fi network or interface, losing required permissions, leaving the Activity, or reaching its 60-second elapsed-time window ends the session. There is no background discovery service, boot start, scheduled scan or automatic resume. Start again explicitly. If Android pauses execution, cleanup can occur when execution resumes; callbacks beyond the discovery window are rejected.

Ending a session requests Android cleanup; a stop request alone is not confirmation that native discovery/resolution has ended. While cleanup is pending, SWEEP retains exclusive scan ownership and blocks another live scan or competing radio mode. **Retry Android cleanup** issues bounded stop/close retries only, without starting a new browse. Ownership is released only after real terminal callbacks confirm cleanup. If Android never confirms termination, force-stop the app in Android settings and reopen it before attempting another live session.

The receiver requests these **eight service types**:

- `_http._tcp.` and `_https._tcp.`
- `_rtsp._tcp.` and `_ipp._tcp.`
- `_airplay._tcp.` and `_raop._tcp.`
- `_googlecast._tcp.` and `_hap._tcp.`

These are protocol names, not device classifications. Each session retains at most **128 advertised-service records**, with at most one resolution attempt per advertised identity and one native resolution active at a time. Each resolution has an eight-second wait limit; unsupported cancellation can end discovery rather than allow another unresolved native operation. A resolved result retains at most eight addresses. The interface reports service types started/failed, resolved/unresolved results, scope and dropped results. A handset may reject some simultaneous discovery requests; a requested type is not necessarily a successfully observed type.

Android performs multicast service discovery and resolution. SWEEP does not connect to advertised endpoint ports, make endpoint HTTP requests, scan port ranges, send router API requests or collect traffic payloads. The alpha neither reads nor stores NSD TXT attributes. It does not obtain live client MAC addresses, Wi-Fi signal strength, encryption state or recording activity from this feature. See Android's [NSD overview](https://developer.android.com/develop/connectivity/wifi/use-nsd).

## What a result means

An NSD record contains the advertised instance name and service type, any address/port returned by resolution, and the times this phone **received the first and last NSD results**. Those times are not packet age or proof of a fresh on-air advertisement. Android can return cached metadata, and one-shot resolution can become stale immediately. SWEEP labels receipt time and age rather than claiming that a client is currently online. [Android resolution limitations](https://developer.android.com/reference/android/net/nsd/NsdManager#resolveService(android.net.nsd.NsdServiceInfo,%20java.util.concurrent.Executor,%20android.net.nsd.NsdManager.ResolveListener)).

Several services can belong to one physical device. Addresses and advertised names can change, be reused or be misleading; they are evidence within a snapshot, not permanent device identity. A streaming or web service can be a candidate for further inspection, but the protocol alone does not prove a camera, microphone or recorder. An empty result does not establish a safe room or an empty network. Isolated clients, other subnets, devices on another network and clients that advertise no supported service can be absent. No physical location is inferred.

## Generic router JSON import

**Import router JSON inventory** opens Android's document picker only after a separate authorization confirmation. It reads a UTF-8 JSON file no larger than **1 MiB**. This is an owner-supplied **generic schema**, not a promise to accept arbitrary router exports or an integration that logs into a router. An export may need conversion outside SWEEP. No router credentials, URL or API token are accepted.

Schema version 1 has exactly these root fields:

- Required `schemaVersion`: integer `1`.
- Required `capturedAt`: positive integer timestamp in Unix epoch **milliseconds**, no later than this phone's import time.
- Required `devices`: array with at most **128** records; an empty array is allowed.
- Optional `networkLabel`: text, up to 120 characters.

Each device object permits these optional fields:

- `hostname`: text, up to 255 characters.
- `addresses`: array of up to 16 literal IPv4/IPv6 address strings; it can be omitted. Hostnames and URLs are not valid address entries, and validation performs no DNS lookup.
- `macAddress`: six hexadecimal bytes separated by colons or hyphens.
- `reportedIdentifier`: text, up to 128 characters, supplied by the inventory owner.
- `current`: boolean reported by the exporter.
- `lastSeenAt`: positive integer epoch-millisecond timestamp, no later than `capturedAt`.

A record needs at least one reported hostname, address, MAC address or identifier. Unknown fields, duplicate JSON keys, duplicate records, repeated reported MACs/identifiers, reused literal addresses, malformed values and unsupported control characters are rejected. Ambiguous leases must be resolved in the input; SWEEP does not merge them into a supposed device identity. TXT fields and credentials are outside the import schema.

This **synthetic format example** does not describe a discovered device:

```json
{
  "schemaVersion": 1,
  "capturedAt": 1760000000000,
  "networkLabel": "Authorized test network",
  "devices": [
    {
      "hostname": "example-device",
      "addresses": ["192.168.50.20"],
      "macAddress": "02:00:00:00:00:20",
      "reportedIdentifier": "inventory-entry-20",
      "current": false,
      "lastSeenAt": 1759999900000
    }
  ]
}
```

Imported source timestamps stay separate: `capturedAt` is the exporter's reported capture time; `lastSeenAt` and `current` belong to that export; `importedAt` is this phone's receipt time. Imported records have no NSD first/last observation times. Importing a report neither tests present reachability nor independently verifies its values. An old export imported today is still old reported evidence.

## Local history and sharing

Network history is separate from RF session history and stays in the app's private storage. The store retains at most **20 snapshots** and applies a **seven-day** cutoff when loading or writing, based on local session start/import time, rather than the router's reported capture time. There is no background expiry job. Process-death checkpoints recover as interrupted with an unknown end time; the interface does not invent a duration. Save/load failures are visible and offer a retry or explicit history clear rather than treating unreadable history as empty.

Review individual evidence records locally. Delete a snapshot or clear all history through confirmation. The import file is not copied into history; only validated records are saved. SWEEP does not retain access to the chosen file for future imports.

**Share snapshot summary** defaults to counts, source, timestamps and coverage. Endpoint names, service names, addresses, MACs, reported identifiers, TXT values and network labels are omitted. **Include identifying evidence** is a separate opt-in for that share. The chosen sharing app receives the selected text; previously shared copies are not removed by local deletion.

## Deferred features and verification

Phone-traffic VPN visibility, HTTPS interception, certificate installation, router login/API adapters, subnet sweeps and network traffic recording remain deferred and are absent from Alpha 3. The phone's own VPN traffic would have a different scope from advertised-service discovery; neither could read another device's encrypted SD card.

This document describes source behavior. Build, regression and emulator results belong in [SWEEP alpha verification](SWEEP_ALPHA.md); they do not establish physical LAN or recorder detection. Real-handset/API/OEM compatibility, service reception on actual networks, network/VPN changes, router-export correspondence, recorder visibility in configured-client mode, timing and battery performance remain **physically unverified**. Test an independently inventoried network and actual recorder in each mode before making detection claims.
