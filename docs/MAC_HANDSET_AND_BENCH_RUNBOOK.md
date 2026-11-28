# Mac Handset Install & Topdon TC001 Max Bench Runbook

This runbook covers tomorrow's first physical phone installation from macOS and next week's hardware bench bring-up when the **Topdon TC001 Max** arrives.

---

## 1. Tomorrow on Your Mac — Pull, Sign Once & Install via ADB

### Step A: Sync repository
```sh
git pull origin main
```

### Step B (Recommended once): Create your permanent upload keystore
Android ties app-private storage (including **Saved Spaces**, baselines, and inspection logs) to the APK signing certificate. Creating your own keystore **before** saving spaces on your phone ensures every future build and Play Store update installs seamlessly with `adb install -r` without ever uninstalling.

```sh
mkdir -p ~/.sweep-keys
keytool -genkeypair -v \
  -keystore ~/.sweep-keys/sweep-upload.jks \
  -storetype PKCS12 \
  -keyalg RSA -keysize 4096 -validity 9125 \
  -alias sweep-upload
```

Export the signing variables in your Mac shell (or add them to untracked `local.properties`):
```sh
export SWEEP_STORE_FILE="$HOME/.sweep-keys/sweep-upload.jks"
export SWEEP_KEY_ALIAS="sweep-upload"
read -s SWEEP_STORE_PASSWORD && export SWEEP_STORE_PASSWORD
export SWEEP_KEY_PASSWORD="$SWEEP_STORE_PASSWORD"
```

### Step C: Build & verify release-signed APKs + Play AABs on macOS
Ensure JDK 17/21 and Android SDK (`platforms;android-36`, `build-tools;36.0.0`, `ndk;28.2.13676358`, `cmake;3.22.1`) are installed, then run:
```sh
./gradlew --no-daemon :app:assembleSweep :app:assembleSweepRadio :app:bundleSweep :app:bundleSweepRadio
python3 scripts/verify_sweep_apks.py --bundle-dir app/build/outputs/bundle
```
*(With `SWEEP_STORE_*` set, `verify_sweep_apks.py` will report `"releaseSigned": true` and `"productionReleaseVerified": true`. If you just want a quick test without building on the Mac, you can also download `sweep-alpha-apk` and `sweep-radio-alpha-apk` directly from the latest green `Android CI` GitHub Actions run.)*

### Step D: Connect Android phone & install
1. On your Android phone, enable **Settings → About phone → tap Build number 7 times**, then **Settings → System → Developer options → USB debugging** (or **Wireless debugging**).
2. Connect via USB-C and tap **Allow USB debugging** on the phone:
```sh
adb devices -l
adb install -r app/build/outputs/apk/sweep/app-sweep.apk
adb install -r app/build/outputs/apk/sweepRadio/app-sweepRadio.apk
```

---

## 2. Day-1 Phone Smoke Checklist (No External Hardware Needed)

1. **Saved Spaces & Turnover PDF (`Scan → Spaces`):**
   - Run a Room Scan at home, tap **Scan → Spaces**, create `"Home"` (or `"My Car"`), attach the completed Room Scan as its **Known-Good Baseline**, and mark your own router/TVs as **Expected**.
   - Run a second Room Scan, start a Space Inspection, complete the guided physical checklist items (`Checked`, `Needs follow-up`, `N/A`), save the log, and tap **Share inspection PDF**.
   - Confirm: (a) PDF renders cleanly in a viewer, (b) MACs/SSIDs/notes are omitted by default and included only when toggled on, and (c) the printed `SHA-256` matches the JSON export `payload` digest.
2. **Thermal UI & ΔT Spotlight Preview (`Scan → Thermal → View synthetic sample`):**
   - Adjust the **ΔT Spotlight** threshold (`+1.5 °C` to `+8.0 °C`), cycle palettes (`Delta Spotlight`, `Ironbow`, `White Hot`), tap pixels for spot °C / ΔT, and verify `0°/90°/180°/270°` co-rotation.
3. **Network Discovery (`SWEEP + Radio → Network`):**
   - Check the authorization box and run a 60-second local Wi-Fi DNS-SD scan; confirm active services resolve and discovery halts cleanly when leaving the screen.
4. **Handset Battery & Doze Check (`Travel → Battery test`):**
   - Run a 1-hour unplugged idle baseline followed by a 1-hour Travel BLE run per [SWEEP_ALPHA.md](SWEEP_ALPHA.md#handset-battery-and-permission-trials).

---

## 3. Next Week — Topdon TC001 Max Hardware Bench Protocol

When the **Topdon TC001 Max** arrives:

### Step A: Capture exact USB descriptors & VID/PID (optional Mac + phone wireless ADB)
To inspect the live USB enumeration while the phone's USB-C port holds the TC001 Max, pair **Wireless debugging** over Wi-Fi first:
```sh
adb pair <PHONE_IP>:<PAIRING_PORT>
adb connect <PHONE_IP>:<CONNECT_PORT>
```
Plug the **TC001 Max** into the phone's USB-C port, then capture Android's USB descriptor dump and SWEEP thermal logs:
```sh
adb shell dumpsys usb > tc001_max_usb_dumpsys.txt
adb logcat -s SweepThermal:* System.err:*
```

### Step B: On-phone functional & radiometric checks
1. Open **SWEEP → Scan → Thermal → Discover USB accessories**.
2. Confirm the TC001 Max appears, tap it, review the in-app **Camera-for-USB-UVC disclosure**, grant runtime `CAMERA` permission, and approve Android's USB accessory dialog.
3. Verify:
   - **Interface selection:** Confirm the dual-lens TC001 Max negotiates the `256×384` `YUY2` (`196,608`-byte `IMAGE_AND_TEMP_OUTPUT`) thermal stream rather than the visible-light CMOS stream, and note its exact USB `vendorId` / `productId` so we can add its exact PID to `ThermalUsbCatalog.kt` if needed.
   - **Spatial & radiometric alignment:** Hold a warm cup or USB wall charger in front of the lens; confirm the grayscale luminance outline and temperature hotspot move together across all 4 rotations (`0°/90°/180°/270°`) and shutter calibration (`0x01`) frames skip without corrupting °C readings.
   - **Saved Space Turnover PDF round-trip:** In **Scan → Spaces**, record a cold fixture baseline (e.g., an unplugged wall adapter), plug the adapter in for 3 minutes, run **Measure USB thermal check** on that checklist item, confirm `THERMAL_DELTA` triggers once peak-above-median rises by `>= +2.0 °C`, and export the redacted **Turnover PDF**.
   - **Hot-unplug safety:** Unplug the TC001 Max mid-stream and reconnect; confirm `libsweep_uvc.so` drains URBs cleanly without ANRs or native crashes.

## 4. tinySA and tinySA Ultra USB bench trial

Physical reception is pending for both models. Repeat this trial for each available analyzer and record its firmware, Android build, antenna/input setup, attenuation and resolution bandwidth.

1. Open **Scan → Spectrum → Discover USB devices**, select the analyzer and approve the Android USB prompt. Check the identified model against the enclosure. For a custom banner, record the manual model selection.
2. Supply a known test signal at a safe input level within a supported preset. Compare the app's peak frequency and dBm reading to the analyzer display using matching settings. Record the band median, peak and prominence. Test single and continuous sweeps, rotation, backgrounding, denial, detach and reconnect. Test 5.8 GHz separately on an Ultra configured for extended harmonic mode and label the result uncalibrated.
3. Measure a Saved Space band baseline, change the reference level and attach a new check during an inspection. Verify the signed peak and prominence changes and the +10.0 dB threshold in the turnover log. Check that PDF, text and JSON omit USB strings by default and that the synthetic preview cannot attach. [Wire protocol and band limits](WIDEBAND_SPECTRUM.md)
