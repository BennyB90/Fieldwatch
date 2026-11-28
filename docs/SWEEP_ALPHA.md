# SWEEP Android alpha

SWEEP now has its own native app rather than wrapping the Fieldwatch instrument interface. It continues to reuse Fieldwatch's Wi-Fi/BLE receivers, signature engine, catalog and Remote ID decoder, preserving MIT attribution and the original third-party notices.

## What you can do

- Start and end a scan on demand; a foreground notification offers an end-session action. Room observations stop being accepted after a 20-minute elapsed-time window, including when queued callbacks are processed late. Phone sleep can delay radio teardown until the service resumes.
- View tracker, camera and drone indicators, other recognized devices and unmatched radio observations. Open a finding for actual match evidence, signal strength, observation age and practical next checks.
- See reported aircraft and control-station/takeoff/fixed positions on a local coordinate plot. Coordinate decoding time is kept distinct from the latest radio reception. Opening an external maps app requires a tap.
- Review saved sessions after closing or restarting the app, share a report, delete one session or clear local history.
- Compare two completed saved room scans. Review newly observed, not reobserved, shared and unresolved addresses alongside the recorded radio coverage. Changed identifiers, missed scans and unequal coverage remain explicit limits.
- Open **Scan → Spaces** in either SWEEP variant to save a room, rental unit, office or vehicle. Choose a completed Room Scan for its known-good baseline, then attach a later scan to an inspection. Only exact transport/address pairs match. Without a baseline, unfamiliar observations are unresolved.
- Open **Scan → Thermal** for the USB thermal viewer and ΔT Electronics Spotlight. Saved Spaces can record a live fixture baseline and include a later thermal check in a Turnover PDF. A rise of at least +2.0 °C in peak above scene median flags the item for physical review. Physical reception on the ordered Topdon TC001 Max awaits bench testing. [Thermal inspection and Android USB access](THERMAL_INSPECTION.md)
- Inspect measured signal history inside a finding. Up to 64 real readings are retained; older reports can have no history. Charts do not provide direction or distance.
- Enable **Travel** explicitly for repeated tracker observations. Location correlation is off by default and records fresh phone GPS fixes only when separately enabled. A visible foreground notification offers Stop; interruptions never auto-resume. Familiar-address exclusions, location revocation and clearing travel history are available in Travel.
- Search the complete inherited 254-signature catalog. Entries are recognition rules, not experimentally verified detection counts.
- Explore a clearly labeled sample session. Sample results are synthetic, do not access radios and are never saved as physical scan history.
- In **SWEEP + Radio**, open **Network** for authorized Wi-Fi service discovery and generic router JSON imports. Live discovery requires Android 13+; imported inventories and saved snapshots support Android 10+. Service records are candidates for investigation, without recorder classification. See [network discovery](NETWORK_DISCOVERY.md).

## Saved Spaces and turnover logs

Record a human checklist outcome for each spot, including N/A for skipped checks. A saved log retains the space name, scan times, coverage, comparison counts, up to 24 detail rows per group, and the checklist as reviewed. Updating the baseline, renaming the space, or rotating Room Scan history preserves that log. Private storage holds up to 25 spaces, 500 baseline addresses and 250 expected devices per space, and the latest 30 inspections per space. Unreadable history offers retry and explicit clear.

Choose **Share inspection PDF**, **Share text**, or **Share JSON** from a saved log. Identifiers and notes, and separately coordinates, default off. The space label appears on the log. Each format shows the SHA-256 of the same compact UTF-8 JSON `payload` object, in export field order, without a trailing newline. The JSON envelope includes both the payload and its digest. Changing a disclosure option produces a new digest. The phone keeps up to eight temporary inspection shares and prunes files older than 24 hours at startup and export.

On November 27, 2026, the Windows JVM suites passed 819 tests each for Debug, Spike and SWEEP, and 846 for SWEEP + Radio, with zero failures, errors or skips. Each included 17 thermal protocol and engine tests and 32 Saved Spaces tests. Lint reported zero errors and 107 warnings per SWEEP variant, with zero warnings in the thermal and native sources. Both APKs passed the permission, signature, backup and ZIP alignment checks. Both APKs and both AABs contained the thermal bridge for four ABIs with every ELF load segment aligned to 16,384 bytes. Handset validation of USB reception, the rendered PDF, Android sharing and RF performance is pending.

## Installation

The build uses JDK 17 or 21, Android SDK platform 36, NDK 28.2.13676358 and CMake 3.22.1.

```sh
./gradlew --no-daemon :app:testSweepUnitTest :app:assembleSweep
adb install -r app/build/outputs/apk/sweep/app-sweep.apk
```

*(On Windows PowerShell, use `.\gradlew.bat` and `python` in place of `./gradlew` and `python3`.)*

The app is labeled **SWEEP** and uses the separate package `app.fieldwatch.sweep`. It requires Android 10+ and targets API 36 (Android 16). Without release keystore credentials configured, this alpha is locally debug-signed and non-debuggable; supplying `SWEEP_STORE_*` credentials produces publisher-signed APKs and Google Play `.aab` bundles (`:app:bundleSweep`, `:app:bundleSweepRadio`). Unused library code/resources are shrunk; the verified installer is about 9.8 MB.

Open SWEEP and tap **Start scan**. Android requests Location, Bluetooth and applicable Nearby Wi-Fi permissions; notifications allow the visible scan notification. Android's Wi-Fi scan API requires Location access and enabled Location services. Room scans do not request phone GPS fixes or retain the phone's location. Travel location correlation is a separate opt-in choice. An available radio may continue when another radio is missing, off or denied; the coverage panel describes the limitation. Use Settings to enable missing permissions/radios, then return to SWEEP.

## Local data and sharing

The SWEEP manifest removes Internet permission. No account, cloud upload, catalog fetch, geocoder, map SDK, analytics client, or TAK publisher runs in the SWEEP application. The normal Fieldwatch build remains an independent comparison instrument.

The separate **SWEEP + Radio** build has Internet and multicast permissions for explicit network discovery and listening. Network discovery ends when its screen leaves view, retains at most 20 local snapshots for seven days on load/write, and shares the existing radio-mode lock. Android cleanup must be confirmed before another live scan can acquire that lock. Router imports accept a bounded generic JSON schema, with reported capture times separate from phone import times. Network reports omit identifying evidence by default. No VPN traffic capture, endpoint connection or router login is implemented.

Sessions are stored in the app-private directory with atomic replacement. The store retains up to 100 sessions and 1,000 recent radio findings per session. Active sessions are checkpointed; a process-killed session is recovered as interrupted, never silently resumed or marked complete. A new scan waits for the previous scanner's cleanup, and callbacks are tied to their original session and service instance. If Bluetooth cleanup fails, SWEEP retries the original stop while retaining radio ownership. If the retries fail, force-stop and reopen SWEEP to scan again. Android may terminate a scan or omit radio traffic; dropped samples and incomplete coverage remain visible.

Both SWEEP variants explicitly exclude app storage from Android cloud backup and device transfer, in addition to disabling backup. This addresses Android's documented [OEM transfer exception](https://developer.android.com/identity/data/autobackup) to `allowBackup=false`; actual OEM migration behavior remains a handset check. Travel records an erasure request before updating the visible history. If SWEEP restarts before rewriting the archive, it hides the old coordinates and retries the erasure. An unreadable Travel archive blocks replacement until the user explicitly clears it. A backward phone-clock correction does not discard future-dated local Travel evidence; normal capacity limits still apply and age-based expiry resumes as the clock advances.

Report sharing omits broadcast names, identifiers and coordinates by default. Names/identifiers and coordinates have separate explicit options. The Android share sheet sends the chosen report to another app; an external maps action sends the chosen coordinate to that app. Local deletion cannot remove copies already shared to another app.

## What this alpha establishes

The local build and automated tests cover parsing, report privacy, session storage, ordered persistence and interrupted-session recovery. The compiled APK was checked for the separate SWEEP application identity and absence of Internet permission. These checks do not establish that the native interface or foreground-service lifecycle works on a particular phone.

October 12, 2026 verification: all 603 JVM tests passed in each of the Debug, Spike and Sweep variants; all 20 Python evidence-harness tests passed. The SWEEP APK assembled successfully with minimum API 29 and target API 35. The original instrument and spike remain separate build variants.

Android lint completed with zero errors and 50 inherited warnings, primarily compatibility simplifications, dependency versions, launcher-icon variants and UI conventions. No warning points into the new SWEEP source. Compiled package checks also confirm that the alpha is non-debuggable; its Android APK signature verifies.

Later on October 12, storage became available and an Android 15 emulator booted successfully. The final offline APK installed and opened without a Listen tab; starting, ending, restarting and ending a second scan were verified after granting Android permissions. No physical transmitter was tested. These checks do not validate the permission-dialog experience, a rapid teardown race, notification actions, process interruption, screen-off/Doze, report sharing/deletion, older Android versions or other OEMs. Those remain acceptance checks on a physical phone, along with sensitivity, false positives, reception range, positioning accuracy and battery consumption.

The app does not hold a wake lock or keep the screen on. The 20-minute evidence window is enforced when observations are processed; it is not an exact wake-up alarm. Android sleep can delay radio teardown and reduce coverage. Reports keep the actual recorded end time and battery reading, so a delayed stop is not presented as a measured 20-minute battery trial. A wall-clock correction greater than five seconds relative to elapsed time interrupts room or Travel collection with a visible explanation and an unknown end time. Starting another session is explicit. See Android's [CPU wakefulness guidance](https://developer.android.com/develop/background-work/background-tasks/awake) and [Doze behavior](https://developer.android.com/training/monitoring-device-state/doze-standby).

The hardware spike remains required. Camera AP detection depends on the camera's operating mode; Wi-Fi clients, silent/offline recorders, cellular equipment and unsupported radios are outside these scan APIs. Bluetooth long-range reception depends on the handset. Wi-Fi Remote ID vendor fields require Android 11+ here. The new Wi-Fi NAN discovery path requires handset Wi-Fi Aware availability and permissions, and its protocol-2 framing has software tests. Physical reception is still unverified. A matching signal is a clue to investigate, not a room-safety certification or proof of surveillance.

The new room-comparison and signal-chart interfaces are original implementations inspired by survey and analyzer workflows; no WiGLE or VREM code was copied. Travel observations are an original implementation informed by AirGuard's research and documented limitations; no AirGuard source was copied. Reference projects: [WiGLE](https://github.com/wiglenet/wigle-wifi-wardriving), [WiFiAnalyzer](https://github.com/VREMSoftwareDevelopment/WiFiAnalyzer), [AirGuard](https://github.com/seemoo-lab/AirGuard). The NAN transport follows the [OpenDroneID receiver's service framing](https://github.com/opendroneid/receiver-android) and [Android Wi-Fi Aware lifecycle](https://developer.android.com/develop/connectivity/wifi/wifi-aware).

## Alpha 2 verification on October 13

A full unfiltered run passed 693 JVM tests in each of Debug, Spike and Sweep, and 719 in SweepRadio, with zero failures, errors or skips. All 20 Python evidence-harness tests passed. All four APK variants assembled. Sweep and SweepRadio lint have zero errors and the same 50 inherited warnings. Both delivered APKs are signed and non-debuggable; the offline package has no Internet permission, and neither package requests background location.

An Android 15/API 35 emulator verified the native comparison of two completed scans, Travel start/stop, room-scan exclusion while Travel owns the radios, default location-off with no GPS request, notification navigation to Travel, explicit opt-in GPS requests, removal of active GPS requests after revocation, successful erasure acknowledgment, no auto-resume after force-stop, and clearing Travel history across restart. Permissions were granted through the test harness; permission dialogs and physical handset behavior remain unverified. No physical tracker or drone was exercised. NAN protocol framing and delayed callback fences have software tests, not proof of reception on a handset.

The integration review found and repaired notification navigation and channel visibility, normalized NAN peer-identity collisions, incomplete GPS-erasure visibility/recovery, and stale generic tracker assessments after a saturated phone-address cache. Regression tests cover those failure paths. A final regression also fences the four-hour Travel evidence window independently of delayed Android teardown, and the service checks expiration before GPS/notice work. Physical sensitivity, false positives on real journeys, radio range, NAN handset compatibility, screen-off/Doze behavior and battery drain remain hardware acceptance work.

The rig plan and pending evidence remain in [SCOPE.md](../SCOPE.md) and [spike/README.md](../spike/README.md).

## Alpha 3 network verification on October 13

A full unfiltered run passed 734 JVM tests in each of Debug, Spike and Sweep, and 760 in SweepRadio, with zero failures, errors or skips. All 20 Python evidence-harness tests passed. All four APKs assembled. Both SWEEP variants have zero lint errors and the same 50 inherited warnings. Version code 37 installers are signed and non-debuggable; the offline variant has neither Internet nor multicast permission nor a Network activity, and neither variant requests background location. No VPN service is declared.

The Android 15/API35 emulator exercised the actual Network screen and authorization checkbox, discovery of a clearly synthetic Android DNS-SD advertisement, resolution to that fixture's addresses and port 54321, manual Stop and subsequent Start, interruption when leaving the screen, force-stop recovery with unknown duration and no automatic restart, generic router JSON import with separate capture/import times, rejection of unsupported credential fields without replacing valid evidence, and default-off identifying evidence in the share dialog. Android's native service-discovery state independently showed no remaining SWEEP requests after manual Stop and settled screen exit. Clearing network history remained empty after process restart. No report was sent to a recipient.

The integration review and native run found and repaired Unicode-name/storage mismatches, synthetic timeout timestamps masquerading as Android receipts, cross-session native resolution overlap, unresolved cleanup after failed Stop calls, a premature stopped-status callback during restart, and Android's leading-dot resolved-service type format. Failed cleanup now retains handles and the original radio lease, with bounded cleanup-only retries and a visible recovery path. Source/network rejection remains strict.

These checks establish software behavior on one emulator. They do not establish physical recorder detection, fresh on-air reception, real-router completeness, API33/OEM compatibility, VPN-change behavior on actual phones, range, false-alert rates or battery consumption. The [network guide](NETWORK_DISCOVERY.md) and [actual-recorder protocol](../spike/recorder-protocol.md) describe the capability limits and pending hardware evidence.

## Alpha 4 code audit on October 14

The audit repaired clock-sensitive Wi-Fi pacing, enforced the room evidence window independently of delayed service ticks, and preserved Travel history through backward clock changes and unreadable archives. Both variants now have explicit backup/transfer exclusions. The 2484 MHz channel label is corrected to channel 14.

Remote ID positions retain the type belonging to their last valid coordinates. BLE, beacon and NAN message packs share structural/content validation; malformed packets cannot regain map pins through the catalog field decoder. Custom UUIDs resembling the Bluetooth Remote ID UUID remain distinct. A valid nine-message reference pack survives Wi-Fi information-element extraction. The field-decoding cache now has a 512-entry limit and includes the entire decoding configuration in its key.

A fresh review checked the fixes together and closed the NAN validation inconsistency found during that review. The unfiltered JVM run passed 760 tests in each of Debug, Spike and Sweep, and 786 in SweepRadio, with no failures, errors or skips. All 20 Python evidence-harness tests passed. These are software regressions and reference-encoder fixtures, not real-transmitter measurements.

All four APK variants assembled. Both SWEEP variants have zero lint errors and 49 warnings. Version code 38 / Alpha 4 installers passed the compiled artifact checks below, including signature verification and backup/transfer exclusions. The prior Alpha 3 installer was independently rejected by the new exclusion check as a negative control.

## Alpha 5 audit, Windows parity and API 36 migration

A full codebase and Android 16 (API 36) audit upgraded AGP to 8.9.1 and migrated `compileSdk` and `targetSdk` to 36 (version code 39). Key fixes and additions:

- **Private storage & Windows parity**: `NetworkSessionStore` and `ScannerFeedStore` now apply `0700`/`0600` atomically through `PosixFileAttributeView` on Android/POSIX (closing a `0000` mid-write crash window and temp-file umask race) while verifying readable/writable access cleanly on Windows NTFS. All 770 JVM tests in Debug, Spike and Sweep and all 797 JVM tests in SweepRadio pass with zero failures on Windows as well as Linux, alongside 20 spike Python tests and 5 router-converter Python tests.
- **Session, Travel & radio hardening**: Room scans preserve `firstSeenAt`, monotonic observation counts, and sticky Remote ID pins across `DeviceStore` refreshes, prioritize matched findings over `OTHER` rows at the 1,000-finding cap, sort saved findings consistently, and mark Recents task-dismissals `INTERRUPTED`. `TravelObservationService` includes `FOREGROUND_SERVICE_TYPE_LOCATION` while scanning BLE beacons (keeping GPS strictly opt-in) and preserves active tracker alerts when unrelated phones or ignored trackers are filtered. `ScannerRadioService` uses `C.WAKE_MODE_NETWORK` for screen-off Wi-Fi streaming, `RdioScannerActivity` wires older-call pagination and ID3-tagged AAC support, `NetworkDiscoveryRadio` validates `InetAddress` literals before state updates, and `ValentineBleController` retries dropped detailed-alert requests up to three times.
- **Android 16 UX, Play AABs, tools & static site**: All six SWEEP Activities enforce legible light edge-to-edge system bars (`enableSweepEdgeToEdge`), native predictive back on root screens, `WindowInsets.safeDrawing` cutout/landscape padding, and configuration-change resilience. `HandsetBatteryTest` verifies active audio state, enforces a one-minute minimum run, and exports redacted measurement summaries with Travel sighting/GPS counts. `scripts/convert_router_inventory.py` converts common router exports into `schemaVersion: 1` JSON; `scripts/verify_sweep_apks.py` verifies both APKs and Play `.aab` bundles cross-platform; and `site/` + `scripts/stage_pages_site.py` provide a zero-tracker Cloudflare Pages download and privacy site.

## Handset battery and permission trials

Install the current SWEEP APK on an Android 10+ phone. Both variants include the meter under **Travel → Battery test**. Run the first permission checks before collecting battery readings. Start Travel, deny a requested permission and check the explanation. Grant precise location and, when requested, Nearby devices and Notifications, then start again. Switch off Android Location during a GPS-enabled test and check that collection stops. Check the notification Stop action and restart Room and Travel in turn. After a force-stop and reopen, starting another session requires an explicit tap. If testing location erasure, use **Stop location correlation and erase saved locations** and verify the report after reopening.

1. Unplug the phone, stop all scans and playback, then tap **Start measurement** in **Battery test** for an idle baseline. Lock the screen for about one hour. Return to the meter, confirm the phone stayed unplugged and in the same mode, and tap **Finish measurement**.
2. Repeat on the same route with the same phone and a supported tracker you own. Start Travel before starting the meter. Run balanced Bluetooth with GPS off, then **Use low-power Bluetooth** with GPS off. For the GPS comparison, repeat balanced Travel with **Also compare my phone's locations** checked. Finish each measurement before stopping Travel. Keep screen use, Android battery saver, connectivity and other apps consistent.
3. Compare whole-phone percentage points lost per hour with the idle baseline. The meter takes two battery readings and uses elapsed time including deep sleep. A drop from 84% to 81% in 90 minutes is 2.0 points/hour. Android rounds the displayed battery level, so short runs can be noisy. Repeat close results, compare spaced sightings for the same tracker, and record the fraction of sightings with a usable GPS fix. Repeat on another Android brand before choosing a default.

The meter keeps the latest 12 completed readings locally, offers **Share measurements** (with phone model/Android version withheld unless opted in, and Travel sighting/GPS-fix counts included without addresses or coordinates), and provides **Clear measurements**. It checks power and active audio/scan state at the endpoints and requires at least one minute of elapsed time before finishing. Discard the run if the phone charged or if scanning or playback was interrupted at any time between readings. Use a separate trial for permission changes. These exploratory measurements are separate from the [20-minute hardware acceptance control](../spike/README.md#battery-controls).

For an audio smoke test, install **SWEEP + Radio**. Serve the repository's [30-second test tone](../app/src/testSweepRadio/resources/fixtures/sweep-radio-test-tone.wav) from a direct HTTPS URL you control. Open **Listen → Add a feed**, save that URL, allow internet audio, and press Play. Check playback on the speaker, lock the screen, and exercise the notification controls. For an hour-long playback battery run, use a continuous authorized feed, begin playback before opening the meter, select **Label this run as radio playback**, and finish the measurement while it is still playing.

## Repeatable installer checks

After assembling the two SWEEP variants, run the same artifact checks used by CI:

```sh
python3 scripts/verify_sweep_apks.py --sdk "$ANDROID_HOME"
```

The verifier reads the compiled APK manifests and backup resources, checks the exact permission sets and exposed components, verifies APK signatures, checks ZIP and 64-bit ELF alignment for 16 KB pages, and prints installer hashes. These checks apply to the files to be installed, including merged library declarations. They do not certify physical performance or production signing.

## Play release work

Both SWEEP variants now compile against and target **API 36 (Android 16)**, assemble Play `.aab` bundles (`:app:bundleSweep`, `:app:bundleSweepRadio`), wire release keystore signing (`SWEEP_STORE_*`), enforce light edge-to-edge system bars and predictive back, and strip unused `<queries>` and sideload `DEPENDENCY_INFO_BLOCK` metadata. See [docs/PLAY_STORE_RELEASE.md](PLAY_STORE_RELEASE.md) for Play Console Data Safety, foreground-service declarations, signing commands, and remaining physical-handset acceptance checks, and [docs/CLOUDFLARE_PAGES.md](CLOUDFLARE_PAGES.md) for static APK distribution.
