# USB thermal inspection

Open **Scan → Thermal** in SWEEP or SWEEP + Radio. Connect a compatible accessory to the phone’s USB host port, tap **Discover USB accessories**, select the device and confirm the Tiny1-C / P2 profile. Use **View synthetic sample** to try the viewer without hardware.

**Physical USB-C reception on the ordered Topdon TC001 Max remains unverified until bench testing.** The bench check needs to establish whether Android grants access, which descriptors and transfer sizes the device advertises, whether live image and temperature planes correspond as the scene changes, and how unplugging and reconnecting behave.

## UVC wire format

The receiver enumerates USB video interfaces and reads their advertised formats, frame dimensions, intervals, endpoints and alternate settings. The operator confirms the profile because an ordinary video camera can advertise the same dimensions. SWEEP prefers 256×384 YUY2/YUYV and a 400,000-unit interval (25 frames per second) when advertised. It sends a VideoStreaming PROBE, reads back the selected mode and payload limits, then sends the accepted block unchanged in COMMIT.

| Advertised mode | Bytes per complete frame | Interpretation |
| --- | ---: | --- |
| 256×384 `IMAGE_AND_TEMP_OUTPUT` | 196,608 | The first 256×192 plane contains YUYV image data. The second plane contains one little-endian unsigned 16-bit Kelvin×64 sample per image pixel. Surface temperature in °C is `u16 / 64.0 - 273.15`. |
| 256×192 YUY2/YUYV | 98,304 | The viewer extracts luminance. Spot temperatures, ΔT measurements and attachment to Saved Spaces require the combined mode. |

In the combined profile a final byte of `0x01` marks a shutter/calibration frame. SWEEP counts and skips it. Temperature samples of `0x0000` and `0xffff` are excluded from measurements. Rotation applies the same coordinate transform to luminance and temperature.

## Transport and Android 16 USB access

Bulk endpoints use Android’s USB bulk transfer API. For isochronous endpoints, the receiver chooses the smallest alternate setting with sufficient payload capacity. The NDK bridge, `libsweep_uvc.so`, borrows the file descriptor from the authorized `UsbDeviceConnection`. One worker selects the alternate setting and submits eight USBFS isochronous URBs with 32 packets each. It reaps completions, checks packet status and requeues buffers. Both transports strip UVC headers, track frame ID (FID) and end of frame (EOF), and discard damaged or wrong-length frames. On shutdown the native worker discards outstanding requests, drains completions and restores alternate setting zero before Kotlin releases the interface and closes the connection.

The build targets arm64-v8a, armeabi-v7a, x86 and x86_64. The linker gives the bridge’s ELF `PT_LOAD` segments 16 KB (16,384-byte) alignment. The package verifier checks the bridge inside each APK and AAB and runs Android’s 16 KB ZIP alignment check on each APK.

USB host and camera hardware features are declared optional (`android:required="false"`). Discovery is user-initiated. Because Android 9+ (`targetSdk >= 28`) requires `android.permission.CAMERA` before `UsbManager` will grant USB access to any USB Video Class (`USB_CLASS_VIDEO`) input device, the foreground Thermal screen requests runtime `CAMERA` permission only when the operator confirms connecting to a USB thermal accessory—displaying an explicit disclosure that SWEEP uses USB only and never opens the phone's built-in cameras. Once `CAMERA` is granted, `ThermalUsbController` checks `UsbManager.hasPermission` and, when necessary, requests access to the selected device using a package-scoped pending intent. Its private receiver checks the request generation, device and actual grant before claiming both the UVC `VideoControl` and `VideoStreaming` interfaces. Detach, disconnect and leaving the screen invalidate that session. Reopening requires an explicit connection. [Android UsbManager permission requirements](https://developer.android.com/reference/android/hardware/usb/UsbManager#requestPermission(android.hardware.usb.UsbDevice,%20android.app.PendingIntent))

Thermal uses USB independently of the Wi-Fi/BLE radio lease. A Room or Travel foreground service can continue during inspection. Live Network discovery ends when its own activity leaves view. USB reception does not change the recorded RF coverage.

## ΔT Electronics Spotlight

The median of valid frame temperatures estimates scene ambient. Tap a pixel for its surface temperature and difference from that median. Spotlight highlights four-connected regions at or above the selected ΔT (default +2.5 °C, adjustable from +1.5 to +8.0 °C). Regions larger than 15% of the frame return to grayscale and count as broad surfaces. A compact highlight identifies a spot to inspect physically. Sunlight, charging and normal equipment load can produce the same pattern.

## Saved Spaces and the Turnover PDF

1. Open **Scan → Spaces**, select the space, expand **Thermal baselines** and choose a fixture. Aim at the item and attach a fresh live radiometric frame. The baseline stores capture time, median, peak and peak-minus-median (ΔT) in tenths of a degree.
2. During an inspection, attach a completed Room Scan, then use **Measure USB thermal check** on the matching checklist item. Aim from a comparable position. Record the human checklist outcome separately.
3. The comparison uses the current peak-minus-median minus that fixture’s recorded baseline ΔT. A rise of **+2.0 °C or more** adds a `THERMAL_DELTA` warning. The Spotlight slider only changes the live overlay. Replacing a baseline preserves the values and comparison already archived in an inspection.

The PDF, text and JSON exports use the same canonical payload, including measured item count, capture times, decimal °C values and available baseline differences. Raw frames stay in memory. Synthetic sample and image-only frames cannot create fixture baselines or inspection evidence. USB product, manufacturer and serial strings, when available, are private by default and appear only with **Include identifiers**. Coordinates have a separate opt-in. The saved `reportSha256` covers the default redacted report. The `archiveSha256` covers the report with both disclosure options enabled, including private USB details. SWEEP checks both digests before exporting a thermal inspection.
