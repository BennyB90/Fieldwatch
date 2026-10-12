# SWEEP detection spike evidence protocol

This folder prepares the bench experiment. It does not access radios, emulate transmitters, order hardware, or supply detection results. `evidence-template.json` contains **240 pending trials**, ten pending office baseline controls, and one pending battery control. All measurement fields start as `null`. Unit-test fixtures are software examples and must never enter a hardware evidence appendix.

The positive matrix is four targets (AirTag, Tile Mate, a Wi-Fi camera in AP mode, and an ESP32 broadcasting synthetic BLE Remote ID) × three distances (1, 5, 10 m) × two environments (quiet and office) × ten independent trials. The second ESP32 is a spare or comparison emitter; it is not a real-aircraft substitute. Remote ID over Wi-Fi, real aircraft interoperability, hidden microphones, and silent/wired cameras need separate predeclared experiments.

## Running it

Python 3.9+ and its standard library are sufficient. From the repository root:

```sh
python3 spike/evidence.py template spike/my-run.json
python3 spike/evidence.py summarize spike/my-run.json --output spike/my-run-summary.json
python3 -m unittest discover -s spike/tests -v
```

The creation and summary commands refuse to overwrite existing files. Keep a fresh run file, source screenshots/captures, hardware inventory, and the protocol declaration together. `evidence.schema.json` describes the record shape. The summarizer enforces measurement validity and gate semantics; JSON Schema alone does not establish freshness, physical truth, or performance.

## Before the first trial

1. Record the phone ID/model, Android version/API (Android 10 is API 29), app build commit, and scan settings. Identify each physical target and its reference advertising identity/BSSID/Broadcast ID independently of the app's classifier. Record firmware or explicitly record `unknown`, ownership/paired state, power state, AP mode, and any changes between trials. Tracker behavior can vary with owner proximity and device state.
2. Define quiet/office locations and measured separation. Keep target orientation and phone placement repeatable; record obstructions/interference and environment details in `notes`. Randomize the trial order and record the actual start time. Stop/restart the session for each independent trial; do not reuse an earlier observation as a new detection.
3. The camera screening gate is predeclared in `SCOPE.md#measurement-protocol`: **zero camera alerting sessions in all ten 60-second office negative controls**. The template records `max_session_false_alarm_fraction: 0.0`, `minimum_valid_sessions: 10`, `declared_before_first_trial: true`, and that declaration reference. Its denominator is `valid_negative_office_sessions`: a session is an alarm session if it produces one or more camera-class alarms, regardless of packet/alarm count. Preserve the declaration before collecting results; missing declaration fields cannot pass. All ten controls must be valid, and even one alerting session fails this strict screening gate. This is a small-rig screening bar, not evidence of a population false-positive rate.
4. Inventory the office's known devices and camera indicators. The negative control requires the reference test camera to be physically absent, with an inventory reference. Count all unwanted camera-class alarms as `camera_alarm_count`. Call this the **negative-session alarm rate**: inventory-matched office cameras may explain alarms, but must not be silently removed from the declared count. This metric does not prove that every alert was physically false or that the office contains no hidden devices.
5. Confirm that the scanner records a live session and sample timestamps. Independently verify the appropriate radio: tracker/emulator trials require `ble_scan_running_verified: true` and `ble_scan_evidence_ref`; camera trials and office controls require `wifi_scan_completed: true` and `wifi_scan_evidence_ref`. A running app or generic live-scan flag is insufficient. Office Wi-Fi baselines must also contain at least one received scan result; an empty or disabled scan cannot produce a passing zero-alarm baseline. The declared freshness limit is five seconds at ingestion. Android Wi-Fi results may come from a cache: record the maximum sample age, even if it invalidates the trial. Do not turn cached sightings into new detections. If there are no received samples in a positive trial, record `samples_received: 0` and `max_sample_age_seconds: null`; record verified radio scanning and `signal_available: false` when the controlled device is silent. A valid silent-device trial is still a detection failure in the full denominator.

## Recording a trial

Use the existing ID and planned fields. Fill the metadata and measurements, then set `status` to `complete`. Every positive trial runs the full **60-second observation window**, including early successes. `latency_seconds` is measured from this session's start to the first matching observation. `detected: false` requires `latency_seconds: null`, `classification: null`, and `matches_reference_identity: false`. This records a measured failure instead of dropping it.

`detected` records an RF indicator returned by the scanner; `classification` records its observed class. A tracker success requires `ble_tracker` plus a match to the separately known reference target. A wrong classification or unmatched candidate is a valid failed trial. `ground_truth.physically_confirmed` records the operator's separate physical check; it is never inferred from the RF classification. Attach log/screenshot/capture references, and record signal availability separately from a successful classifier result.

Mark a measurement `invalid` and explain `invalid_reason` if permission loss, scanner interruption, stale cache, ground-truth uncertainty, or timing failure prevents a valid measurement. Invalid, missing, and pending trials remain in coverage and cannot establish a passing gate. Do not replace or silently repeat a failed/invalid trial after seeing the outcome. Preserve it and predeclare a separate rerun.

## Remote ID locations and source type

The planned source is `synthetic`, and the physical item is `esp32_emulator`. Firmware must emit protocol-conforming BLE Remote ID, not an arbitrary UUID or name that resembles a drone. Supply a raw capture reference and protocol identification; set `protocol_valid` only after decoding and checking the intended message fields against the emitter configuration. A protocol-unqualified radio sighting cannot provide a trusted pin.

Keep location fields distinct:

- `locations.aircraft`: decoded aircraft location with `message_location_type: "aircraft"`.
- `locations.operator`: decoded location explicitly qualified as the operator location with `message_location_type: "operator"`.
- `locations.takeoff`: a takeoff/launch location with `message_location_type: "takeoff"`.

Each populated location contains numeric `latitude` and `longitude`; the unavailable-coordinate sentinel `(0, 0)` cannot qualify as a pin. A missing location remains `null`. Do not label a takeoff point as the operator's current location. The harness rejects mismatched semantics and synthetic/real relabelling. It reports the three pin types separately, and real-aircraft validation remains `not_evaluated` even after a synthetic decoder pass. The synthetic gate requires all 60 planned emulator trials valid plus at least one protocol-qualified aircraft pin; it establishes decoder integration only, not reliable drone detection at every tested distance.

## Battery controls

Duplicate the pending battery record with a new ID for every additional phone/session before running it. Every phone used in valid positive trials needs its own valid battery control. Record the start/end percentage, UTC timestamps, screen/radio settings, and absence of charging. The session must last **exactly 1,200 seconds**; timestamps and the duration field must agree. The gate is drain **strictly less than ten percentage points** (100% → 90% fails). A pass on one phone never covers another. Repeated sessions on the same phone all must pass for that phone to pass; an invalid or unfinished session remains incomplete.

## Interpreting the summary

- Tracker gates are separate for AirTag/Tile and quiet/office at 10 m. Each requires ten valid trials and at least nine reference-matched classifications within 60 s. Each critical cell must use the same phone ID/model/OS/API and scanner build/settings; mixing these contexts returns `invalid_cell`. The summary shows those contexts explicitly. Failed/no-detection trials remain in the denominator of ten; missing/invalid trials cannot be turned into a smaller successful denominator. Results at 1/5 m do not rescue the 10 m gate.
- Camera positive coverage and matching-indicator counts are separate from the office negative-session alarm rate. The predeclared baseline screening bar is zero alerting sessions in ten valid controls; pending measurements never pass it. A minimal positive gate requires all 60 planned camera trials valid plus at least one matched AP flag. No universal camera detection-rate threshold is invented; predeclare one in a new protocol if needed.
- Coverage reports planned, valid, pending, invalid, and missing trials, with reasons. `matrix_cells` shows all 24 cells, valid failures, signal-unavailable trials, explicit denominators, and latency for matching hits. A locally passing gate does not mean the entire spike is complete. `spike_status` can pass for the declared spike only after all 240 trials are valid and every gate passes. Check office baseline and every tested phone's battery result before making the promised spike claims.
- An RF indicator does not establish a hidden camera, actual operator identity, precise distance, or a physical device's absence. Silent, unsupported, offline, and wired devices may be invisible. Only observations supported by the completed rig belong in the evidence appendix.

The fixed matrix and thresholds are `sweep-spike-v1`. Changes to targets, trial counts, environments, scan duration, freshness, or source semantics require a new protocol rather than editing a successful subset into this one.
