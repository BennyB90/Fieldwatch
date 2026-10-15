# Optional public-safety radio listening

The `sweepRadio` Android variant adds **Listen** beside Scan, Sessions and Catalog. It installs as **SWEEP + Radio** (`app.fieldwatch.sweep.radio`). The `sweep` variant remains **SWEEP**, with no Internet permission or Media3 player dependency.

The listening screen saves feed names, regions, categories and favorites locally. Add a direct HTTPS audio stream whose operator permits its use in this app, then choose whether to allow Internet listening. Playback requires a saved feed and an explicit Play action. No feeds ship with the alpha. The user's chosen open-source radio project is [chuot/rdio-scanner](https://github.com/chuot/rdio-scanner); the additional **Connect to Rdio** screen plays individual recorded calls from an operator-installed HTTP extension.

Audio arrives over the Internet from the feed operator's receiver. This function does not tune the phone to police VHF/UHF channels, decode trunked radio, decrypt encrypted communications, or establish that a delayed feed is real-time. MP3, AAC and HLS audio are supported through AndroidX Media3 1.8.1; direct streams are required, not a web-player page or a generic playlist directory. Source availability and device codecs remain dependencies.

## Controls and privacy

- Add, search, favorite and delete saved feeds; play, pause and stop a chosen feed.
- Audio can continue while leaving Listen or using another app, with Android media controls. Dismissing the task stops playback. Headphone disconnection and audio focus use Android's player behavior.
- Revoke listening consent to stop playback; clear radio settings to remove feeds and consent. Nothing automatically starts playback on app launch or process recovery.
- The chosen feed provider and its delivery hosts receive IP/connection information and may log requests. SWEEP does not send RF findings, tracker IDs, drone coordinates or phone GPS to them. Radio settings are separate from RF session history.
- There are no advertising SDKs, recording, transcription, audio archives, directory scraping or automatic location lookup in the Android module. Saved feed URLs can contain access tokens; they remain in app-private settings and are not included in RF reports. Media diagnostics are disabled to avoid logging URL tokens. Rdio uses the selected server's existing listener access code; the Android app does not create accounts or retain the code on disk.
- Only HTTPS addresses are accepted. User/password URLs, fragments, control characters and invalid ports are rejected. Feed errors shown to the user do not repeat the URL.

## Feed access

Open-source scanner software and access rights to a particular operator's audio are separate. Broadcastify's current [catalog API policy](https://support.broadcastify.com/hc/en-us/articles/204740425-Live-Audio-Feed-Catalog-API) excludes new police-scanner listening applications. Its [terms](https://www.broadcastify.com/terms/) require separate rights for programmatic playback. This alpha therefore contains no Broadcastify catalog, credentials or copied streams and rejects direct Broadcastify/RadioReference source addresses.

## Rdio recorded-call connection

Rdio is a server and web client that distributes recordings supplied by scanner/SDR receivers. Inspected upstream revision: `4f92b8648bb91d51149bb1603570422a075fcf4b`. Its [API policy](https://github.com/chuot/rdio-scanner/blob/main/API_ACCESS_POLICY.md) permits third-party HTTP REST use, but the actual source exposes administrative and recorder-upload routes over HTTP; listener configuration, call lists and playback use its WebSocket channel. SWEEP does not use that channel, admin credentials or invented stock playback endpoints.

The separate GPL-3.0-or-later [SWEEP HTTP extension](../integrations/rdio-http/README.md) adds versioned configuration, call-list and audio routes to an operator's pinned Rdio server. It requires operator installation and HTTPS. The Android adapter is independently written and contains no Rdio server/web-client source. The extension is not included in the APK and is not remotely installed or hosted by this work.

In Listen, choose **Connect to Rdio**, enter the HTTPS server base address and its listener access code if required, and choose Connect after allowing Internet listening. The first request loads accessible system/channel labels and up to 50 recent calls. Refresh is manual; a call plays only after tapping Play. The code is sent in HTTPS POST bodies, never a URL. The companion rechecks expiry and channel scope for each request. Access codes with a positive concurrent-listener limit are explicitly unsupported by the stateless companion rather than bypassing that limit.

Rdio playback is foreground-only. Disconnect, leaving the screen or revoking consent cancels requests, clears the code and received lists, and releases audio. Server address and access code exist only in the open activity, not saved settings or Android recovery state. Audio is bounded to 8 MiB per call and passed to the player in memory, with no disk archive/cache. Requests refuse redirects and have finite connection/read/response limits; malformed responses and errors do not display backend bodies, codes or private addresses. RF session history and reports are not sent.

This is a manual recorded-call browser, not an automatically advancing live channel stream. No Rdio operator URL, credentials, real police recordings or receiver coverage have been supplied or verified. Software fixtures establish adapter behavior only.

## Build and verification

```sh
./gradlew :app:testSweepRadioUnitTest :app:lintSweepRadio :app:assembleSweepRadio
adb install -r app/build/outputs/apk/sweepRadio/app-sweepRadio.apk
```

The current radio variant passes 642 JVM tests (616 shared, including 13 Valentine protocol tests, plus 12 feed tests and 14 Rdio adapter tests). Debug, Spike and offline SWEEP each pass the 616 shared tests. Both SWEEP variants pass Android lint with zero errors and 50 inherited warnings. The 20 spike evidence tests still pass. The Rdio companion clean-applies to pinned upstream, passes the full Go test suite including ten companion tests, and compiles with an explicitly test-only webapp embed fixture. Builds use Android API 35 and are locally debug-signed, non-debuggable, and shrunk.

Android 15 emulator checks verified native navigation, feed creation, consent before playback, HTTPS fetching and decoding of the deterministic test tone, pause, stop, background media playback, consent revocation, favorite persistence after a process restart, and clearing feeds/consent. The final APK was installed again and verified to open Listen with no saved feeds and consent off. A loading-state bug found during these checks was fixed by observing controller connection state; a pending Play request is not saved for process recovery. Native playback state was observed through Android's media session; host audio output was disabled in the headless emulator, so no physical speaker/listening test was performed.

The deterministic 30-second tone under `app/src/testSweepRadio/resources/fixtures/` is software playback test data, not police audio, and is not included as an app feed. Physical phone/radio reception, actual police-feed integration, provider coverage, task-dismiss/screen-off behavior on physical phones and combined scanning/listening battery use remain unmeasured. Android 10–14 and other OEMs have not been tested.

New Rdio native Android 15 checks verified navigation, consent before connecting, a sanitized failure against the reserved `.invalid` host, access-code clearing after backgrounding, and revocation shared with Listen. A real server connection, native Rdio call playback and operator coverage remain unverified; the adapter/server tests use synthetic fixtures.
