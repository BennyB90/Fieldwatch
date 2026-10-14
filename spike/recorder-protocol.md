# Actual recorder mode and room experiment

This is a human-run extension to the original SWEEP spike. No hardware has been inventoried, ordered, configured, scanned, or shown to be recording by these files. `recorder-template.json` starts entirely pending, with null measured results. It contains 180 proposed positive trials, 60 controls, and 60 proposed A/B room pairs. These are plans, not completed trials or detections. Keep this extension separate from `sweep-spike-v1`; its template is not input for `evidence.py`.

## Preregister the actual hardware

Before the first trial, complete the declaration timestamp, inventory, handset/build/settings plan, room inventory, and each mode's availability. Use the same phone model, exact OS build, app build, settings, and signature set within a ten-trial cell and every A/B pair. Use normal Android Wi-Fi scan throttling; separately label any development setting changes.

The target must be a physically inventoried **actual audio recorder**: record its make/model, firmware (or explicit `unknown`), unit ID, configuration evidence, and independent evidence of a recording that can be played back. A learned label, device name, manufacturer, or an app's classifier cannot supply that ground truth. The radio-source evidence must independently connect each observed address or advertisement to that unit in that mode. A router association record, device configuration screen, or controlled on/off isolation can support ground truth; recording it does not add a router-query receiver to SWEEP.

Preregister these modes independently:

- `hotspot`: the recorder itself emits a Wi-Fi AP while recording. Inventory its BSSID and advertised name separately from the upstream router.
- `connected_client`: the recorder records while joined to an owned test router. Inventory the client association and its address independently. An AP scan sees the router's BSSID, which must not be matched to the recorder's client address.
- `offline_recording`: the recorder records locally with its Wi-Fi/BLE disabled or otherwise independently verified inactive. A verified fresh scan miss can coexist with successful recording.
- `ble_advertising`: optional, and eligible only if this exact device independently supports and emits BLE advertisements while recording. An installed Bluetooth chip or classic-Bluetooth connection is insufficient. The template has **no BLE trials** until eligibility is established.

Set a supported mode to `supported` with its supporting evidence before measurements. If unavailable, set `availability: false`, `registration_status: "excluded_preregistered"`, the reason, and the declaration evidence; mark its proposed rows `excluded_preregistered` while leaving measurements null. Preserve the rows and report the mode unavailable, without claiming success or treating an unsupported mode as an observed miss. Do not exclude a supported mode because its scanner results were poor. To include supported BLE, copy the 60-cell layout (1/5/10 m × quiet/office × ten repeats) into `optional_ble_trials` with unique IDs before measuring; preregister BLE receiver controls separately. No BLE results may be inferred from Wi-Fi results.

Record storage encryption from device documentation/configuration if known. There is **no remote encryption-detection gate**: these Wi-Fi/BLE receivers cannot establish whether recorded files are encrypted.

## Positive trials and controls

For every supported core mode, run ten independent 60-second sessions at each of 1, 5, and 10 m in quiet and office environments. Measure actual distance, use repeatable placement/orientation, preregister a randomized order, and record actual start/end times. Verify and record the actual mode and actual recording separately for each session. Do not assume a recorder kept recording across a mode change.

Verify the receiver independently: a completed Wi-Fi scan plus a known benign AP on this phone, or a running BLE scan plus a known benign advertiser for eligible BLE trials. Record fresh sample counts, source timestamps/maximum sample age, and the relevant receiver evidence. A running app is insufficient. Pre-session results, repeated cached deliveries, missing radio permissions, and disabled receivers must not become new target hits. Keep a failed supported trial in its denominator; use `invalid` with a reason when receiver or ground-truth failure prevents interpretation. Do not silently replace failures or exclude them after seeing the outcome.

Run the preregistered controls in each environment, ten repeats each:

1. `recorder_absent`: reference recorder physically absent; inventory the background and count any recorder classifications. This checks unwanted alerts against the reference target's absence, without establishing that the room contains no other recorders.
2. `known_good_wifi_receiver`: a separately inventoried benign AP verifies that the phone is receiving fresh AP results. Its visibility must not become evidence that a connected-client recorder is visible.
3. `cache_only_target_removed`: collect a seed sighting of the recorder's independently verified hotspot, physically remove/power it off, then start a new session. Capture whether Android returns cached results. Any returned old target sample must not create a new fresh target/address/classification hit. If no cached sample is delivered, record that case as unexercised for cache rejection, not as a successful cache-rejection test. Exclude this control before trials if the target has no supported hotspot mode.

An empty or broken receiver is invalid control evidence, rather than a clean zero-alert result. A supported client/offline trial with a working receiver but no fresh target sighting remains a valid observed miss. Do not condition rates on trials where the recorder happened to advertise.

## Keep the three results separate

Every session records three independently assessable results:

- **Raw radio visibility:** fresh observations/capture and their source timestamps, transport, name, address, and payload. Radio visibility alone does not identify a recorder or establish recording.
- **Known-address match:** a fresh address matches the independently inventoried unit's address for this mode/session. Address rotation must be documented independently; the same advertised name does not establish the same device.
- **Recorder classification:** the app actually returns a recorder class, its exact reason/rule/evidence, and whether a user-taught label or signature contributed. Record other returned classes too. An address match or learned friendly name alone is not a recorder classification.

Use `false` for a completed observed negative result and null latency for a missed event. Use `null` for unmeasured/unknown results. A stale raw sighting cannot qualify a fresh address or recorder hit. Report valid trials, observed misses, invalid, pending, and preregistered exclusions separately for each mode/distance/environment/phone. No detection-rate target is invented for this extension.

Keep a pretest signature snapshot, training-unit ID, training evidence, and any learned signature/alias IDs. A test on the same taught unit is explicitly a **same-unit follow-up**. It does not establish that an independently hidden unit, the previously discussed incident hardware, or other recorder models were detected. Freeze the signature set before held-out trials; tuning after seeing a failure requires a separately declared rerun.

## A/B room comparisons

The template proposes ten pairs of each scenario in quiet and office environments. Preregister exact room IDs, layout and each member's measured distance. Each pair uses one phone/build/settings/signature snapshot and two fresh 60-second sessions. Randomize the A/B order for the two room/substitution scenarios below; the baseline-then-addition scenario must retain its fixed chronological order. Independent inventory/configuration/recording evidence accompanies both members.

- `baseline_then_recorder_added`: scan A first in an independently inventoried room/background with the actual recorder physically absent. Add the inventoried recorder to **the same room**, retaining the phone position/settings and background; independently verify its presence, selected supported mode, actual recording, and radio source. Scan B later, then explicitly select the earlier A session as the baseline for B. The template fixes the member order to A then B; randomization of other trials must not invert it. A “newly observed” target requires an eligible fresh B observation, source timestamp/capture and independently matched target evidence that was absent from the eligible A observations. Cached/pre-session samples and a reused name cannot establish a newly observed recorder. Confirm the room/background and chronological order separately. If B misses the supported signal while the receiver works, retain the miss and recording ground truth: the comparison failed to observe the added unit and cannot establish that the room remained unchanged or safe.
- `same_recorder_rooms`: move the actual inventoried recording unit between room A and room B, retaining its declared mode/configuration where possible. Record each room's background and any independently verified address rotation. Compare observations; do not infer room membership or distance solely from RSSI.
- `same_name_different_address`: room A contains the actual hotspot recorder; room B contains an independently inventoried **benign controlled AP** with the same advertised name and a different address, with the recorder physically absent from B. Preserve the real target/control identities and addresses. The benign AP is a name-confusion control, not another recorder. A name-only or learned alias transfer must not identify it as the same physical unit. Run this scenario only if the actual recorder has a supported hotspot mode and the benign name/address control can be configured; otherwise preregister an explicit exclusion. A later BLE-name variant needs its own supported-device/control declaration.

Store both room sessions and comparison results, including same-name confirmation, different-address confirmation, address-match outcomes, recorder classifications, and any alias transfer. A different-address radio is new radio evidence; a name match is insufficient to claim the trained unit followed the user into another room.

## Receiver limits and next steps

Current AP scanning does not enumerate ordinary connected Wi-Fi clients or promiscuously capture their traffic. A client-mode miss can be an expected receiver limit even while router evidence proves the actual recorder is associated and recording. Offline recordings may emit no supported observable radio. Such misses do not establish privacy or physical absence.

Alpha 3's SWEEP + Radio build offers a separate [authorized NSD and generic router-inventory path](../docs/NETWORK_DISCOVERY.md). It has no completed physical recorder experiment. Preregister that receiver, the owned/authorized network and access, its evidence and limits before using it in this rig; do not merge cached NSD receipt times or reported router records into fresh passive AP/BLE measurements. Storage encryption and actual audio contents remain separate from radio detection.

Only completed, independently supported measurements can become findings. This extension contains no evidence that an actual recorder or any incident hardware has been detected.
