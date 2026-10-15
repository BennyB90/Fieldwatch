# SWEEP spike build and verification

October 12, 2026. Base commit: `844ace8c5c787695977b2c4857e06533603aba95`. Branch: `sweep/detection-spike` in [BennyB90/Fieldwatch](https://github.com/BennyB90/Fieldwatch).

## Build

Use JDK 17 or 21 and Android SDK platform 35. Set `JAVA_HOME` to the compatible JDK and `ANDROID_HOME` to your SDK (or use an ignored `local.properties` SDK path).

```sh
./gradlew --no-daemon :app:testSpikeUnitTest :app:assembleSpike
```

Output: `app/build/outputs/apk/spike/app-spike.apk`. This test APK is locally debug-signed, uses `app.fieldwatch.sweep.spike`, and can be installed alongside Fieldwatch. It requires Android 10+ and targets API 35. It is not a Play Store release or production publisher-signed build.

The final spike run passed **552 JVM tests, 0 failures, 0 errors, 0 skipped**. The evidence harness passed **20 tests**. This includes stock catalog, signature, parser, and store tests, plus session isolation, the Wi-Fi timestamp regressions and independent C-encoder Remote ID fixtures. A successful compile verifies neither phone radio behavior nor the visible interface on a physical phone.

## Install and run

```sh
adb install -r app/build/outputs/apk/spike/app-spike.apk
```

Open **SWEEP Spike**, tap **Start scan session**, and grant the requested radio/location permissions. Android location permission/services are needed for these scan APIs. Tap **End session** to stop the scan. The inherited Fieldwatch instrument interface and first-run acknowledgment remain inside this shell.

This initial shell does not produce a finished consumer session report. Each explicit spike start clears transient instrument sightings, decoded pins and history; the shell follows the service's actual scan state. When evaluating the rig, keep an external trial ID and stopwatch/capture. Persisted logs are preserved, so separate them by trial boundaries; an earlier log entry is not evidence of a detection in a new trial.

Do not disable normal Wi-Fi throttling for consumer acceptance. Record settings, phone model/OS build, target state, sample ages, classifier output and source captures. The spike logs every processed sighting; Android and the bounded service queue can still omit radio traffic. It is not a monitor-mode capture or an independent ground-truth receiver.

Use the built-in log export after each trial and keep it with the completed evidence record. With an authorized USB-debugging phone, the separate test package is debuggable, so local logs can also be collected:

```sh
adb shell run-as app.fieldwatch.sweep.spike ls files/logs
adb exec-out run-as app.fieldwatch.sweep.spike cat files/logs/fieldwatch-000.jsonl > trial-log.jsonl
```

Inspect the listed parts and collect every relevant rotated log, rather than assuming file 000 contains the whole trial. Export before resetting app data. Logs can include observed identifiers and coordinates; use controlled rig data and user-directed export.

## Evidence harness

See [the spike protocol](../spike/README.md). Its template has 240 pending trials; it supplies no fabricated detections. The summarizer keeps misses, invalid trials, pending trials, synthetic/physical separation, map semantics, baseline coverage and battery conditions visible.

## Verification limits

No Android handset was attached when the build was checked (`adb devices` returned an empty list). Physical installation, session interaction, detection sensitivity, false positives, Remote ID reception, map accuracy and battery measurements remain pending. The current `spike` UI is an initial engineering shell around Fieldwatch, not the completed SWEEP consumer design.

Remote ID C fixtures can be reproduced from the [pinned reference generator](../app/src/test/reference/opendroneid/README.md). Software regression tests protect known decoding/cache behavior and do not establish real-world detection rates.
