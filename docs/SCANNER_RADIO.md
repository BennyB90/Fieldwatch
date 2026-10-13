# Optional public-safety radio listening

The `sweepRadio` Android variant adds **Listen** beside Scan, Sessions and Catalog. It installs as **SWEEP + Radio** (`app.fieldwatch.sweep.radio`). The `sweep` variant remains **SWEEP**, with no Internet permission or Media3 player dependency.

The listening screen saves feed names, regions, categories and favorites locally. Add a direct HTTPS audio stream whose operator permits its use in this app, then choose whether to allow Internet listening. Playback requires a saved feed and an explicit Play action. No feeds ship with the alpha. The exact open-source “Police Scanner” project mentioned by the user has not yet been identified; no code or catalog from it has been imported.

Audio arrives over the Internet from the feed operator's receiver. This function does not tune the phone to police VHF/UHF channels, decode trunked radio, decrypt encrypted communications, or establish that a delayed feed is real-time. MP3, AAC and HLS audio are supported through AndroidX Media3 1.8.1; direct streams are required, not a web-player page or a generic playlist directory. Source availability and device codecs remain dependencies.

## Controls and privacy

- Add, search, favorite and delete saved feeds; play, pause and stop a chosen feed.
- Audio can continue while leaving Listen or using another app, with Android media controls. Dismissing the task stops playback. Headphone disconnection and audio focus use Android's player behavior.
- Revoke listening consent to stop playback; clear radio settings to remove feeds and consent. Nothing automatically starts playback on app launch or process recovery.
- The chosen feed provider and its delivery hosts receive IP/connection information and may log requests. SWEEP does not send RF findings, tracker IDs, drone coordinates or phone GPS to them. Radio settings are separate from RF session history.
- There are no accounts, advertising SDKs, recording, transcription, audio archives, proxy/relay server, directory scraping or automatic location lookup in this module. Saved feed URLs can contain access tokens; they remain in app-private settings and are not included in RF reports. Media diagnostics are disabled to avoid logging URL tokens.
- Only HTTPS addresses are accepted. User/password URLs, fragments, control characters and invalid ports are rejected. Feed errors shown to the user do not repeat the URL.

## Feed access

Open-source scanner software and access rights to a particular operator's audio are separate. Broadcastify's current [catalog API policy](https://support.broadcastify.com/hc/en-us/articles/204740425-Live-Audio-Feed-Catalog-API) excludes new police-scanner listening applications. Its [terms](https://www.broadcastify.com/terms/) require separate rights for programmatic playback. This alpha therefore contains no Broadcastify catalog, credentials or copied streams and rejects direct Broadcastify/RadioReference source addresses.

[Rdio Scanner](https://github.com/chuot/rdio-scanner) and [ThinLine Radio](https://github.com/Thinline-Dynamic-Solutions/ThinLineRadio) are open-source server projects rather than verified sources for the user's named Android app. An authorized operator endpoint can be considered once its playback API and access rules are known. Their server clients/adapters are not implemented in this alpha.

## Build and verification

```sh
./gradlew :app:testSweepRadioUnitTest :app:lintSweepRadio :app:assembleSweepRadio
adb install -r app/build/outputs/apk/sweepRadio/app-sweepRadio.apk
```

The radio variant passes 615 JVM tests (603 shared + 12 feed-validation/storage tests) and Android lint with zero errors. The offline SWEEP variant's 603 JVM tests still pass. The APK builds using the project's Android API 35 SDK and is locally debug-signed, non-debuggable, and shrunk.

Native Android 15 emulator checks are in progress. The deterministic 30-second tone under `app/src/testSweepRadio/resources/fixtures/` is software playback test data, not police audio, and is not included as an app feed. Physical phone/radio reception, actual police-feed integration, provider coverage and combined scanning/listening battery use remain unmeasured.
