# Valentine radar accessory alpha

Both `sweep` and `sweepRadio` include a **Radar** tab. It opens a foreground accessory screen for Valentine One BLE hardware. RF scans must end before accessory discovery/connection; SWEEP keeps ownership of its scan service through teardown so the two operations do not compete for Bluetooth scanning.

The external detector senses radar/laser. Android displays the data it receives: detector display character, signal bars, band and direction indicators, and supported detailed alert tables with frequency in MHz and raw front/rear strength values. Arrows are relative to the detector's orientation, not phone GPS or a mapped police location. Strength is not a distance estimate, and raw alert strengths are unitless 0–255 values.

## Hardware and connection

[V1 Gen2 has built-in Bluetooth](https://www.valentine1.com/v1-detectors/). Compatible Gen1 detectors use [V1connection LE](https://www.valentine1.com/v1-detectors/v1-radar-detector-apps/compatibility-android-le/); full ESP data requires compatible detector firmware. This alpha supports the published short BLE service/characteristics and checksum/non-checksum V1 ESP display/alert frames. Bluetooth Classic adapters and long/chunked ESP configuration packets are outside this implementation.

Power the detector, close other detector apps that may hold its connection, then choose Radar → Discover. Discovery lasts at most 15 seconds and lists only advertisements carrying the supported Valentine service UUID. Choose a discovered accessory explicitly. Connecting and subscribing to notifications are bounded to 20 seconds; unsupported services, permission changes and missing fresh display messages result in a visible error. Discovery is not proof of a genuine Valentine device; compatibility is checked by the exposed services and received protocol data.

Discovery in this build requires precise location permission and Android location services because the shared scan manifest does not assert neverForLocation. Android 12+ additionally requires nearby-device scan/connect permission. Connecting a device already discovered in this foreground screen needs connect permission on Android 12+. SWEEP does not request phone GPS fixes or record accessory identifiers, radar observations or locations in RF reports. Disconnecting or leaving the foreground closes the BLE connection and clears received state. No connection resumes automatically.

The sole ESP request sent is `reqStartAlertData` (`0x41`) to subscribe to the supported detailed read stream, after a valid detector display identifies the packet format. The app exposes no mute, mode, custom sweep, factory reset or detector programming actions. A rejected detailed-stream request does not imply radar detection has stopped; the UI explains whether display-only data remains available.

## Provenance and verification limits

Protocol source: official [ValentineResearch/AndroidESPLibrary](https://github.com/ValentineResearch/AndroidESPLibrary), pinned at `7e2c8492b18f798b8b8345bebf83281627f738ce`. UUIDs and field definitions retain the original MIT attribution; the full license is packaged at `app/src/main/assets/licenses/valentine-mit.txt` and available from the Radar information dialog. SWEEP is an independent integration, not an official Valentine product.

Protocol regression tests use valid display vectors from the official specification and separately marked reconstructed alert replays. The 2016 PDF's printed alert examples have an inconsistent payload-length byte; malformed examples are rejected, and corrected replay vectors are labeled as reconstructed. Synthetic examples in the UI are visibly marked **SAMPLE · SYNTHETIC ALERTS** and are dismissed before real discovery/connection. They are not hardware evidence.

Physical Gen2/Gen1 LE discovery, connection, firmware compatibility, incoming alerts, laser indications, time to display, real-world range, screen-off behavior and battery use remain unverified. A real detector and Android handset are required for those tests. Software builds and emulator views cannot establish reception or police-radar identification.

The current build passes 13 Valentine protocol tests within all four Android test variants and both SWEEP lint checks. Android 15 native checks verified navigation, visibly synthetic alerts, Location/nearby permission prompts, safe behavior after Location denial, the active-RF-scan entry gate, and bounded discovery ending without an accessory. The full MIT notice was also verified in the packaged APK and information dialog. No physical BLE accessory was present.
