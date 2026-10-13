# SWEEP Android alpha

SWEEP now has its own native app rather than wrapping the Fieldwatch instrument interface. It continues to reuse Fieldwatch's Wi-Fi/BLE receivers, signature engine, catalog and Remote ID decoder, preserving MIT attribution and the original third-party notices.

## What you can do

- Start and end a scan on demand; a foreground notification offers an end-session action. The service checks a 20-minute limit while Android is running it. Phone sleep can delay the stop until the service resumes.
- View tracker, camera and drone indicators, other recognized devices and unmatched radio observations. Open a finding for actual match evidence, signal strength, observation age and practical next checks.
- See reported aircraft and control-station/takeoff/fixed positions on a local coordinate plot. Coordinate decoding time is kept distinct from the latest radio reception. Opening an external maps app requires a tap.
- Review saved sessions after closing or restarting the app, share a report, delete one session or clear local history.
- Compare two completed saved room scans. Review newly observed, not reobserved, shared and unresolved addresses alongside the recorded radio coverage. Changed identifiers, missed scans and unequal coverage remain explicit limits.
- Inspect measured signal history inside a finding. Up to 64 real readings are retained; older reports can have no history. Charts do not provide direction or distance.
- Enable **Travel** explicitly for repeated tracker observations. Location correlation is off by default and records fresh phone GPS fixes only when separately enabled. A visible foreground notification offers Stop; interruptions never auto-resume. Familiar-address exclusions, location revocation and clearing travel history are available in Travel.
- Search the complete inherited 254-signature catalog. Entries are recognition rules, not experimentally verified detection counts.
- Explore a clearly labeled sample session. Sample results are synthetic, do not access radios and are never saved as physical scan history.

## Installation

Build with JDK 17 or 21 and Android SDK platform 35:

```sh
./gradlew --no-daemon :app:testSweepUnitTest :app:assembleSweep
adb install -r app/build/outputs/apk/sweep/app-sweep.apk
```

The app is labeled **SWEEP** and uses the separate package `app.fieldwatch.sweep`. It requires Android 10+ and targets API 35. This alpha is locally debug-signed and non-debuggable, not a Play Store release. Unused library code/resources are shrunk; the verified installer is about 9 MB.

Open SWEEP and tap **Start scan**. Android requests Location, Bluetooth and applicable Nearby Wi-Fi permissions; notifications allow the visible scan notification. Android's Wi-Fi scan API requires Location access and enabled Location services. Room scans do not request phone GPS fixes or retain the phone's location. Travel location correlation is a separate opt-in choice. An available radio may continue when another radio is missing, off or denied; the coverage panel describes the limitation. Use Settings to enable missing permissions/radios, then return to SWEEP.

## Local data and sharing

The SWEEP manifest removes Internet permission. No account, cloud upload, catalog fetch, geocoder, map SDK, analytics client, or TAK publisher runs in the SWEEP application. The normal Fieldwatch build remains an independent comparison instrument.

Sessions are stored in the app-private directory with atomic replacement. The store retains up to 100 sessions and 1,000 recent radio findings per session. Active sessions are checkpointed; a process-killed session is recovered as interrupted, never silently resumed or marked complete. A new scan waits for the previous scanner's cleanup, and callbacks are tied to their original session and service instance. Android may terminate a scan or omit radio traffic; dropped samples and incomplete coverage remain visible.

Report sharing omits broadcast names, identifiers and coordinates by default. Names/identifiers and coordinates have separate explicit options. The Android share sheet sends the chosen report to another app; an external maps action sends the chosen coordinate to that app. Local deletion cannot remove copies already shared to another app.

## What this alpha establishes

The local build and automated tests cover parsing, report privacy, session storage, ordered persistence and interrupted-session recovery. The compiled APK was checked for the separate SWEEP application identity and absence of Internet permission. These checks do not establish that the native interface or foreground-service lifecycle works on a particular phone.

October 12, 2026 verification: all 603 JVM tests passed in each of the Debug, Spike and Sweep variants; all 20 Python evidence-harness tests passed. The SWEEP APK assembled successfully with minimum API 29 and target API 35. The original instrument and spike remain separate build variants.

Android lint completed with zero errors and 50 inherited warnings, primarily compatibility simplifications, dependency versions, launcher-icon variants and UI conventions. No warning points into the new SWEEP source. Compiled package checks also confirm that the alpha is non-debuggable; its Android APK signature verifies.

Later on October 12, storage became available and an Android 15 emulator booted successfully. The final offline APK installed and opened without a Listen tab; starting, ending, restarting and ending a second scan were verified after granting Android permissions. No physical transmitter was tested. These checks do not validate the permission-dialog experience, a rapid teardown race, notification actions, process interruption, screen-off/Doze, report sharing/deletion, older Android versions or other OEMs. Those remain acceptance checks on a physical phone, along with sensitivity, false positives, reception range, positioning accuracy and battery consumption.

The app does not hold a wake lock or keep the screen on. The 20-minute limit is checked by the running service rather than an exact wake-up alarm; Android sleep can pause that check and reduce radio coverage. See Android's [CPU wakefulness guidance](https://developer.android.com/develop/background-work/background-tasks/awake) and [Doze behavior](https://developer.android.com/training/monitoring-device-state/doze-standby).

The hardware spike remains required. Camera AP detection depends on the camera's operating mode; Wi-Fi clients, silent/offline recorders, cellular equipment and unsupported radios are outside these scan APIs. Bluetooth long-range reception depends on the handset. Wi-Fi Remote ID vendor fields require Android 11+ here. The new Wi-Fi NAN discovery path requires handset Wi-Fi Aware availability and permissions, and its protocol-2 framing has software tests. Physical reception is still unverified. A matching signal is a clue to investigate, not a room-safety certification or proof of surveillance.

The new room-comparison and signal-chart interfaces are original implementations inspired by survey and analyzer workflows; no WiGLE or VREM code was copied. Travel observations are an original implementation informed by AirGuard's research and documented limitations; no AirGuard source was copied. Reference projects: [WiGLE](https://github.com/wiglenet/wigle-wifi-wardriving), [WiFiAnalyzer](https://github.com/VREMSoftwareDevelopment/WiFiAnalyzer), [AirGuard](https://github.com/seemoo-lab/AirGuard). The NAN transport follows the [OpenDroneID receiver's service framing](https://github.com/opendroneid/receiver-android) and [Android Wi-Fi Aware lifecycle](https://developer.android.com/develop/connectivity/wifi/wifi-aware).

## Alpha 2 verification on October 13

A full unfiltered run passed 693 JVM tests in each of Debug, Spike and Sweep, and 719 in SweepRadio, with zero failures, errors or skips. All 20 Python evidence-harness tests passed. All four APK variants assembled. Sweep and SweepRadio lint have zero errors and the same 50 inherited warnings. Both delivered APKs are signed and non-debuggable; the offline package has no Internet permission, and neither package requests background location.

An Android 15/API 35 emulator verified the native comparison of two completed scans, Travel start/stop, room-scan exclusion while Travel owns the radios, default location-off with no GPS request, notification navigation to Travel, explicit opt-in GPS requests, removal of active GPS requests after revocation, successful erasure acknowledgment, no auto-resume after force-stop, and clearing Travel history across restart. Permissions were granted through the test harness; permission dialogs and physical handset behavior remain unverified. No physical tracker or drone was exercised. NAN protocol framing and delayed callback fences have software tests, not proof of reception on a handset.

The integration review found and repaired notification navigation and channel visibility, normalized NAN peer-identity collisions, incomplete GPS-erasure visibility/recovery, and stale generic tracker assessments after a saturated phone-address cache. Regression tests cover those failure paths. A final regression also fences the four-hour Travel evidence window independently of delayed Android teardown, and the service checks expiration before GPS/notice work. Physical sensitivity, false positives on real journeys, radio range, NAN handset compatibility, screen-off/Doze behavior and battery drain remain hardware acceptance work.

The rig plan and pending evidence remain in [SCOPE.md](../SCOPE.md) and [spike/README.md](../spike/README.md).
