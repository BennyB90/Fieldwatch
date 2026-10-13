# SWEEP — Android alpha and detection scope

Updated October 12, 2026. Codename: SWEEP. Base: [OffGridPete/Fieldwatch](https://github.com/OffGridPete/Fieldwatch), pinned at `844ace8c5c787695977b2c4857e06533603aba95` (app 1.1.26, catalog 95).

## Product judgment

Build a measured Android MVP. A clear, private, on-demand scanner can be useful for travelers, renters, and people investigating an unfamiliar device. These are initial customer hypotheses; mass adoption and willingness to pay have not been established. Success requires useful explanations and trustworthy results, followed by testing with real users.

The app is an RF tool users deliberately download. Its job is to show what the phone observed, why a signature matched, which signals the phone could receive, and what the user can check next. A quiet scan must not imply that a room is free of surveillance.

Android already provides [unknown tracker alerts](https://support.google.com/android/answer/13658562/). SWEEP's proposed distinction is a single user-directed session that combines multiple observable signal classes, evidence details, and a local report. Detection of a tracker advertisement and evidence of unwanted tracking are separate findings.

## In scope

Android 10+ installation, on-demand sessions, local detection, and user-controlled reports. Four views:

1. BLE tracker indicators: recognized advertisements, matching evidence, received signal strength, and observation age. Do not infer ownership or malicious intent from presence.
2. Camera indicators: supported Wi-Fi AP/BLE signatures with the exact match reason and tested device mode. A camera-like name or manufacturer is a clue to investigate.
3. Remote ID: decoded aircraft position and separately labeled **reported control station**, **reported fixed operator**, or **reported takeoff point**, as present. Never combine these into a universal “operator launch point.” Missing fields remain absent; old data needs an age label.
4. Catalog browser: retain the upstream **254 signature entries**. This count is not 254 experimentally verified device types. The checked catalog populates 19 classes.

Hidden microphone detection has no general phone-only implementation in this scope. A mic that records locally, uses cellular or an unsupported radio, or emits no observable advertisement is outside these Wi-Fi/BLE APIs. Likewise, a camera configured as a client of another router may be invisible to AP scanning. These are capability limits, not bugs a larger signature list solves. [Fieldwatch boundaries](https://github.com/OffGridPete/Fieldwatch#what-fieldwatch-is-not), [Android AP scan API](https://developer.android.com/develop/connectivity/wifi/wifi-scan), [Tapo setup modes](https://www.tp-link.com/us/support/faq/2710/).

“No matching signals observed in this session” is acceptable report language. “No cameras,” “safe room,” “all microphones detected,” and “precise distance” are unsupported claims.

## Fork strategy

Fork: [BennyB90/Fieldwatch](https://github.com/BennyB90/Fieldwatch). Working branch: `sweep/detection-spike`.

Reuse `BleRadio`, `WifiRadio`, `Observation`, `Sighting`, `DeviceStore`, `SignatureEngine`, `DefaultCatalog`, and `OpenDroneId`. Upstream already decodes BLE FFFA and Wi-Fi vendor-IE Remote ID and distinguishes operator location types. Validate and extend those paths.

`ScanService` is tightly coupled to Fieldwatch's sits, alerts, GNSS, and TAK features. The new SWEEP alpha uses a separate `SweepApplication`, session coordinator, foreground scan service and Compose interface. It reuses `WifiRadio`, `BleRadio`, `DeviceStore`, the catalog and decoders; it does not initialize Fieldwatch's GNSS, geocoder, TAK or other network features. The spike remains a separate comparison instrument.

Ownership proposed in the supplied plan: Viktor — engine and rig; Carlos — UI; architecture owner — integration. No messages or assignments have been sent to those people from this chat.

Preserve `LICENSE`, `NOTICE`, and Off Grid Pete LLC attribution. The original source is MIT. IEEE/Bluetooth SIG lookup tables and the compiled Fast Pair model list have separate provenance/terms described in `NOTICE`; the MIT statement must not be applied indiscriminately to every bundled data asset.

## Current implementation

An optional `sweepRadio` variant adds Internet audio listening for operator-authorized HTTPS streams, saved feed entries and favorites. It is labeled **SWEEP + Radio** and installs separately as `app.fieldwatch.sweep.radio`. RF detection and reports remain local; listening discloses the chosen provider's connection metadata and requires consent plus an explicit Play action. No police-feed directory is bundled, and the exact user-referenced open-source scanner app is awaiting identification. This is streaming audio from a receiver elsewhere, not direct phone reception or decryption of police-radio channels. See `docs/SCANNER_RADIO.md`. The offline `sweep` variant below retains no Internet permission.

The `sweep` alpha installs separately as `app.fieldwatch.sweep`, labeled **SWEEP**. It includes start/end sessions, four finding views with actual evidence and next checks, local history, searchable catalog, report sharing with names/identifiers and coordinates off by default, deletion, and an offline plot of reported drone positions. A clearly labeled sample mode is never mixed into saved scan history. The service checks a 20-minute session limit while it is running; Android sleep can delay the stop until it resumes.

The SWEEP manifest removes Internet permission. Phone Location permission is needed for Android's scan APIs, but the SWEEP app does not request phone GPS fixes or store the phone's location. External maps and report sharing happen only through explicit user actions. Session storage, sharing and deletion run through one ordered queue; process-killed active checkpoints recover as interrupted, and deletion prevents delayed work from recreating a removed report.

The `spike` build has a separate installation ID, `app.fieldwatch.sweep.spike`, and label, `SWEEP Spike`. It starts scanning after a user starts a session. Ending the session stops the service. The normal Fieldwatch build remains available as the comparison instrument.

Wi-Fi evidence is gated per BSSID using Android's microseconds-since-boot `ScanResult.timestamp`. Pre-session samples, repeated cache deliveries, out-of-order samples, and invalid/future timestamps do not become new hits. Observation time is derived from sample age, rather than when cached data was read.

Remote ID repairs cover transport framing at Wi-Fi counter 13, shorter BLE updates following a message pack, current Wi-Fi payload retention, and re-decoding changing Wi-Fi payloads. Regression fixtures come from a pinned independent OpenDroneID C encoder; regeneration source and hashes are in `app/src/test/reference/opendroneid/`.

The spike logs each processed sighting rather than the inherited sampled subset. This is not a complete RF packet capture: OS omissions, the service queue, and radio hardware still limit what reaches the app. Keep source logs and screenshots for every measured trial.

## Handset and transport coverage

Android 10 is the installation floor, not a promise of every Remote ID transport. At this base revision, Wi-Fi IE parsing requires Android 11+; BLE reception remains handset-dependent. Fieldwatch has no Wi-Fi NAN subscriber layer. Do not advertise NAN reception from this fork until implemented and physically verified.

Android limits normal foreground Wi-Fi scan requests to four per two minutes, and results can be cached. Consumer acceptance runs must keep normal throttling on. Developer-unthrottled runs may be recorded separately but cannot establish consumer detection timing. [Android scan rules](https://developer.android.com/develop/connectivity/wifi/wifi-scan).

Record exact phone model, OS build, radio settings, and firmware. Test BLE legacy, BLE extended/coded, Wi-Fi beacon, and NAN separately. A mixed-mode emulator can hide a missing transport by being received over another one. Consult and then independently verify the [OpenDroneID handset test record](https://github.com/opendroneid/receiver-android/blob/master/supported-smartphones.md).

Remote ID uses transmitted positions; the app does not independently verify those coordinates. FAA Standard Remote ID reports control-station location, while broadcast modules report takeoff location. [FAA message contents](https://www.faa.gov/uas/getting_started/remote_id/3-RID-Remote_ID_Main).

## Hardware rig

- One AirTag, recording generation, firmware, owner-near and owner-separated state.
- One Tile Mate, recording generation, firmware, operating mode and advertiser state.
- One camera with documented AP setup behavior. The proposed first target is Tapo C110 because its setup SSID matches the existing `fleet-tapo` signature. It is a known camera indicator control; it does not stand in for all hidden mini-cameras. Test setup/AP, configured client, offline recording where supported, and off/sleep modes separately.
- Two genuine ESP32-C3-DevKitM-1 boards and data-capable USB cables, using [ArduRemoteID](https://github.com/ArduPilot/ArduRemoteID). The original generic ESP32 does not cover coded/extended BLE. Emulator results must be labeled synthetic.
- Existing Android 10+ phone first. Choose an additional phone only after reviewing the existing model's transport gaps. At least a second OEM/model is needed before broader compatibility claims.
- Before claiming real-world drone reception, add a real Remote ID transmitter or compliant drone; decoding our own emulator proves a narrower result.

Live purchase candidates and availability belong in `docs/PROCUREMENT.md`. No orders or physical-device trials have been completed in this chat.

## Measurement protocol

`spike/evidence.py` creates an initial 240-trial screening matrix: four targets × 1/5/10 m × quiet/office × ten independent 60-second trials, plus office negative controls and per-phone battery controls. All measurements begin as pending/null. This is a screening run; ten trials per cell are insufficient for a broad reliability claim.

Measure raw reception, correct classification, time to correct classification, and ground-truth false alerts separately. Keep missed detections in the denominator. Invalid or unperformed trials cannot yield a pass. Freeze settings and false-alarm tolerances before collection; retain source captures and ground-truth notes. Randomize trial order and record any unavoidable carryover. The spike clears transient sightings and decoded pins at each explicit session start. Persisted logs still need to be collected and separated by trial boundaries.

Repeat tracker critical cells in owner-near, owner-separated, stationary, moving, and rotating-identifier states. Run additional camera modes and each RID transport as separately registered extensions; do not replace the four original targets with easier surrogates. After screening, use a larger held-out physical run and report counts and confidence intervals. An observed 9/10 is a point estimate, not proof that population reliability is at least 90%.

Initial gates:

- Trackers: observed correct classification within 60 seconds in at least 90% of valid 10 m trials **for each tracker and environment**, with all planned trials accounted for.
- Remote ID: valid decoded aircraft coordinate/map pin. Require separately valid, correctly labeled control-station/takeoff fields for any corresponding claim. Synthetic and physical results remain separate.
- Camera: classify the positive setup/AP control; require zero camera false-alert sessions across all ten inventoried 60-second office negative controls. This strict initial tolerance is declared here before any hardware trials and recorded in the template. Each baseline needs independently confirmed Wi-Fi scanning and received samples, so a disabled scanner cannot pass. Zero observed false alerts in ten sessions is a screening result, not proof of a population false-positive rate.
- Battery: less than 10 **percentage points** lost in exactly 20 minutes on each tested phone, without charging, at recorded screen/GPS/radio settings. Run a matched idle control for context. This is an absolute consumption gate, not a claim about incremental scanner power.

## Privacy and consumer UX

Detection stays on device. The SWEEP application has no Internet permission and does not initialize geocoding, catalog fetching, map resources or TAK publishing. It offers explicit deletion and report sharing, records no phone GPS fixes, and omits names/identifiers and reported drone coordinates from shares by default. External maps and sharing hand selected data to another app only after a user action. The separate inherited Fieldwatch instrument retains its own behavior and permissions.

The alpha shows a compact finding, match evidence and age, handset coverage, and a practical next check. It does not assign made-up confidence probabilities. Raw signal strength supports comparisons while moving; it is not a calibrated meter-distance reading. Actual decoding time is retained separately for reported drone coordinates when available.

## Evidence appendix

Physical tracker detection rate: **not measured**.

Physical camera sensitivity and office false alerts: **not measured**.

Physical Remote ID reception/map accuracy: **not measured**.

Phone battery drain: **not measured**.

Alpha build and regression-test results are recorded separately in `docs/SWEEP_ALPHA.md`; spike details are in `docs/SPIKE_BUILD.md`. Passing software tests does not satisfy these physical gates. Phone installation and native interface interaction remain unverified because no physical phone was attached and emulator setup exceeded available Mac storage.
