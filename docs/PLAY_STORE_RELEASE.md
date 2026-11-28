# Google Play Store Release & API 36 Guide

SWEEP (`app.fieldwatch.sweep`) and SWEEP + Radio (`app.fieldwatch.sweep.radio`) compile against and target **Android 16 (API 36)** with a minimum SDK of **Android 10 (API 29)**, meeting Google Play's API 36 submission requirement for new phone apps.

---

## 1. Building Signed Release AABs and Sideload APKs

Without release signing credentials configured, `:app:assembleSweep`, `:app:assembleSweepRadio`, `:app:bundleSweep`, and `:app:bundleSweepRadio` build shrunk, non-debuggable alpha artifacts signed with the local Android debug keystore so handset sideload trials work out of the box.

To build publisher-signed release **Android App Bundles (`.aab`)** for Google Play Console (and publisher-signed `.apk` installers for direct distribution), supply your upload/publisher keystore through environment variables or ignored `local.properties` entries:

| Property / Env Variable | Legacy Fallback | Purpose |
|---|---|---|
| `SWEEP_STORE_FILE` | `FIELDWATCH_STORE_FILE` | Path to `.jks` / `.keystore` upload key |
| `SWEEP_STORE_PASSWORD` | `FIELDWATCH_STORE_PASSWORD` | Keystore password |
| `SWEEP_KEY_ALIAS` | `FIELDWATCH_KEY_ALIAS` | Key alias inside keystore |
| `SWEEP_KEY_PASSWORD` | `FIELDWATCH_KEY_PASSWORD` | Key password |

### Generate an upload keystore (once, keep offline/backed up)
```sh
keytool -genkeypair -v \
  -keystore sweep-upload.jks \
  -storetype PKCS12 \
  -keyalg RSA -keysize 4096 -validity 9125 \
  -alias sweep-upload
```

### Build and verify APKs + Play AABs
On Linux / macOS:
```sh
./gradlew :app:testSweepUnitTest :app:testSweepRadioUnitTest :app:lintSweep :app:lintSweepRadio \
  :app:assembleSweep :app:assembleSweepRadio :app:bundleSweep :app:bundleSweepRadio
python3 scripts/verify_sweep_apks.py --bundle-dir app/build/outputs/bundle
```

On Windows PowerShell:
```powershell
.\gradlew.bat :app:testSweepUnitTest :app:testSweepRadioUnitTest :app:lintSweep :app:lintSweepRadio `
  :app:assembleSweep :app:assembleSweepRadio :app:bundleSweep :app:bundleSweepRadio
python scripts/verify_sweep_apks.py --bundle-dir app/build/outputs/bundle
```

Outputs:
- **Play Store AABs**:
  - `app/build/outputs/bundle/sweep/app-sweep.aab` (`app.fieldwatch.sweep`)
  - `app/build/outputs/bundle/sweepRadio/app-sweepRadio.aab` (`app.fieldwatch.sweep.radio`)
- **Direct sideload APKs** (with Google Play's `DEPENDENCY_INFO_BLOCK` omitted for offline verifiability):
  - `app/build/outputs/apk/sweep/app-sweep.apk`
  - `app/build/outputs/apk/sweepRadio/app-sweepRadio.apk`

When signed with a non-debug certificate and verified with `--bundle-dir`, `scripts/verify_sweep_apks.py` reports `"releaseSigned": true` and `"productionReleaseVerified": true`.

---

## 2. Android 16 (API 36) Behavior Verification Summary

- **Mandatory Edge-to-Edge (`targetSdk = 36`)**: All SWEEP Activities call `enableSweepEdgeToEdge()` with explicit light system-bar styling (`SystemBarStyle.light`) and `Theme.Sweep` (`#F6F5EE` window background), preventing illegible white status/navigation icons when Android System Dark Mode is active, and honoring `WindowInsets.safeDrawing` across camera cutouts and landscape system bars.
- **Large-Screen Resizability & Orientation (`sw >= 600dp`)**: No Activity locks `screenOrientation` or sets `resizeableActivity="false"`. SWEEP Compose Activities handle `orientation|screenSize|smallestScreenSize|screenLayout|uiMode|keyboard|keyboardHidden` seamlessly and guard `onStop()` with `!isChangingConfigurations` so multi-window resizing, foldable transitions, and rotation do not abort active 60-second network discovery, SAF JSON imports, Rdio sessions, or Valentine BLE GATT connections.
- **Predictive Back (`targetSdk = 36`)**: Leaf Activities delegate root back gestures directly to Android's `OnBackInvokedDispatcher` for native cross-activity predictive back animations; inner detail sub-routes enable `BackHandler` only while a child view is open.
- **16 KB Page Size Support**: `verify_sweep_apks.py` enforces 16 KB ZIP alignment (`zipalign -c -P 16 4`) and 16 KB ELF load-segment alignment on any 64-bit native libraries in both APKs and AABs.
- **Backup & Device-to-Device Transfer**: `allowBackup="false"` is paired with explicit `<cloud-backup>` and `<device-transfer>` exclusions across all nine storage domains (`sweep_backup_rules.xml` and `sweep_data_extraction_rules.xml`).
- **Package Visibility**: Inherited `<queries>` declarations unused by SWEEP are stripped from both SWEEP manifests.

---

## 3. Google Play Console Declarations

### A. Data Safety Section (`app.fieldwatch.sweep` — Offline SWEEP)
- **Does your app collect or share any of the required user data types?**
  - **Collected (sent off-device by the developer)**: **No.** The APK declares **zero** `android.permission.INTERNET` permission, contains no analytics, crash-reporting, ad, map, or cloud SDKs, and transmits nothing off the phone automatically.
  - **User-initiated sharing / external intents**: Reports and broadcast drone coordinates leave the device **only** when the user taps **Share** (Android share sheet, redacted by default unless the user opts in to identifiers or coordinates) or taps a decoded Remote ID coordinate to open an external maps app (`geo:` URI). Per Google Play Data Safety rules, purely local storage and user-initiated system share sheets / external-app intents are not background developer "collection," though your privacy policy should explain both clearly.

### B. Data Safety Section (`app.fieldwatch.sweep.radio` — SWEEP + Radio)
- Same local RF scanning guarantees as offline SWEEP, plus two explicit user-initiated network features:
  1. **Listen / Rdio Scanner**: Connects over HTTPS **only** to operator-authorized stream URLs or Rdio servers explicitly entered by the user after enabling Internet listening consent. Those third-party servers receive standard HTTPS request metadata (client IP address and, for Rdio, the listener access code in POST bodies). No RF findings or phone GPS coordinates are sent.
  2. **Network**: Performs 60-second local Wi-Fi DNS-SD (`NsdManager`) multicast discovery on Android 13+ while the Network screen is open and authorized, with no endpoint connections or cloud uploads.

### C. Foreground Service Permissions Declaration (Play Console -> App Content)
Both variants declare:
1. **`FOREGROUND_SERVICE_LOCATION` (`SweepScanService`, `TravelObservationService`)**:
   - **Purpose**: User-initiated, time-bounded wireless scan session (`20 minutes` for Room scan; `4 hours` max for Travel tracker observation) with a persistent foreground notification and one-tap **End session** / **Stop Travel** action. Required by Android's Wi-Fi scan and unfiltered BLE beacon APIs (`ACCESS_FINE_LOCATION` while-in-use; **no** `ACCESS_BACKGROUND_LOCATION` is requested) and optional user-enabled phone GPS correlation in Travel mode. Never starts on boot or in the background.
2. **`FOREGROUND_SERVICE_CONNECTED_DEVICE` (`SweepScanService`, `TravelObservationService`)**:
   - **Purpose**: User-initiated foreground Bluetooth LE / Wi-Fi / Wi-Fi Aware observation of nearby trackers, cameras, and OpenDroneID broadcasts while the visible scan notification is active.
3. **`FOREGROUND_SERVICE_MEDIA_PLAYBACK` (`ScannerRadioService` — `SWEEP + Radio` only)**:
   - **Purpose**: User-initiated HTTPS audio stream playback with standard Android media-session controls so audio continues when the user locks the screen or switches apps, and stops on Stop, consent revocation, or task dismissal.

### D. Prominent In-App Disclosure Text (Location & Camera for USB UVC)
Play policy requires a clear in-app disclosure before runtime location permission prompts when location APIs are used for Wi-Fi/BLE scanning or Travel correlation. SWEEP displays this directly on the Scan, Travel, and Radar screens before requesting runtime permissions:
> *"Android requires location access and location services to scan Wi-Fi and nearby Bluetooth broadcasts. Room scans do not request or store your phone's GPS location. Optional Travel location correlation records phone GPS fixes locally only when you separately enable 'Also compare my phone's locations' before starting Travel."*

For optional USB-C thermal accessories (`Scan → Thermal`), Android 9+ requires `android.permission.CAMERA` before `UsbManager` will authorize any USB Video Class (`UVC`) device (`android.hardware.camera` and `android.hardware.usb.host` are declared `android:required="false"`). SWEEP never requests `CAMERA` during wireless scans and displays this disclosure in the Thermal connect dialog before requesting `CAMERA`:
> *"Android requires Camera permission before granting USB access to USB Video Class (UVC) thermal accessories; SWEEP uses USB only and never opens the phone's built-in cameras."*

### E. Store Listing Claims Checklist (Truthful Representation)
- Present SWEEP as an **on-device wireless observation and evidence tool**, never as a "hidden camera finder guarantee," "safe room certifier," "all-microphone detector," or "stalker detector."
- Clearly note that passive Wi-Fi/BLE scanning cannot detect offline SD recorders, wired cameras, cellular-only devices, or Wi-Fi cameras operating silently as client stations.
