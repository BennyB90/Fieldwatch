# USB wideband spectrum

Open **Scan → Spectrum** in SWEEP or SWEEP + Radio. Connect a tinySA or tinySA Ultra with a USB data cable, discover serial devices and select the analyzer. The wired receiver operates independently of the phone's Room Scan and Travel radios. Android prompts for access to the USB device. No additional manifest permission is required.

Physical USB-CDC reception on tinySA and tinySA Ultra hardware awaits bench verification. Firmware, antenna, input mode, gain, resolution bandwidth and placement affect the readings. A peak shows energy received during the sweep. Nearby phones, keyfobs, routers, smart meters and appliances can produce peaks. Identifying the source requires physical inspection. Short transmissions can fall between samples.

## Sweep bands

Each preset requests 290 points including both endpoints. The adapter accepts 100 kHz through 960 MHz on Basic and 100 kHz through 5.34 GHz in the Ultra calibrated range. The 5.8 GHz preset uses the Ultra extended range. On factory-fresh or reset tinySA Ultra units, enable **CONFIG → MORE → ENABLE ULTRA** (code `4321`) on the analyzer before sweeping above 800 MHz; if the analyzer rejects `scanraw`, SWEEP reports the rejection immediately while keeping the USB serial connection open.

| Preset | Range (MHz) | Hardware | Examples of signals in the band |
| --- | --- | --- | --- |
| `subghz_315_433` | 300–450 | Basic or Ultra | 315 MHz and 433.92 MHz remotes, relays and ISM transmitters |
| `subghz_868_915` | 860–928 | Basic or Ultra | 868/915 MHz ISM, LoRa, tags and meters |
| `cell_uplink_low` | 698–862 | Basic or Ultra | Low-band cellular, including nearby phones |
| `analog_1_2g` | 1,080–1,360 | Ultra | Analog video and L-band links |
| `cell_uplink_mid` | 1,710–1,920 | Ultra | Mid-band AWS/PCS uplinks |
| `ism_2_4g` | 2,400–2,500 | Ultra | Wi-Fi, BLE, frequency-hopping and analog links |
| `unii_5g` | 5,150–5,340 | Ultra | Indoor U-NII-1/2 links |
| `ism_5_8g` | 5,645–5,945 | Ultra extended | Analog video and FPV. Harmonic mode above 5.34 GHz is uncalibrated. |

Choose **Single sweep** or **Continuous sweeps** and tap a trace point to read its frequency, level and difference from the band median. Peaks in contiguous regions at least 12.0 dB above the median are highlighted by default. Adjust the threshold from +6.0 to +24.0 dB. Regions wider than one quarter of the sweep remain visible in the trace and are omitted from the narrow peak highlights. The overall peak still includes those points.

## Serial protocol

The clean-room Kotlin adapter claims CDC-ACM control and bulk IN/OUT data interfaces. It recognizes the common STM32 VCP ID `0483:5740`, including its vendor-specific serial interface. Other CDC-ACM devices require operator confirmation. USB IDs alone cannot identify the instrument model.

The adapter sends CDC `SET_LINE_CODING` for 115200 baud, 8 data bits, no parity and one stop bit, asserts DTR and RTS, drains stale input and sends `\r\nversion\r\n`. Banners containing tinySA4, tinySA Ultra, ZS-406 or ZS-407 select Ultra. A basic tinySA banner selects Basic. Select the model printed on the instrument when the version response is quiet or custom.

1. Send the ASCII command `scanraw START_HZ STOP_HZ POINTS\r\n` with 16–1024 points.
2. Skip echoed command text up to the opening `{` (0x7B). Read exactly three bytes per point in order. The tag is `x` (0x78), followed by the low and high bytes of an unsigned 16-bit value.
3. Check the next byte for the closing `}` (0x7D). A `ch>` prompt can follow. Either sample byte can itself equal a brace or an ASCII prompt character. The decoder tracks its position across USB transfers and rejects corrupt or incomplete frames.
4. Convert each sample to tenths of a dBm using `round((raw / 32.0 - offset) * 10)`, with offset 174 for Ultra or 128 for Basic. Frequency at index `i` is `startHz + (stopHz - startHz) * i / (points - 1)` using integer Hz. Prominence is peak level minus band median in dB.

## Saved Spaces and turnover logs

In a Saved Space, choose **Measure band baseline** and **Use measured sweep** to store a reference. During an inspection use **Measure band check** to attach a new reading. Compare the same preset under similar conditions, including antenna position and instrument settings. A rise of at least **10.0 dB** in either peak level or peak prominence against an earlier same-band baseline adds a physical follow-up to the log. The display highlight slider does not change this comparison.

Raw traces stay in memory. A baseline retains band, capture time, median, peak frequency and level, and prominence. Inspections retain these summaries, applicable baseline differences and local USB metadata. Sample previews are labeled synthetic and cannot be attached.

PDF, JSON and text exports share a canonical payload. Product, manufacturer and serial strings require an explicit identifier opt-in. A space name containing a recognized USB identifier is also withheld by default. A per-inspection SHA-256 commitment binds USB metadata to the redacted payload without disclosing a reusable device fingerprint. SWEEP checks the redacted report digest and the full private archive digest before export. These hashes detect changes relative to the saved digests. They do not authenticate hardware or establish calibration.
