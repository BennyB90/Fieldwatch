# SWEEP HTTP companion for Rdio Scanner

This optional server patch adds three finite HTTP POST endpoints for SWEEP's native call browser. It is disabled unless the server operator sets `SWEEP_RDIO_HTTP=1`. It does not use, modify, disable restrictions on, or connect to Rdio's WebSocket API. Install it only on a server you operate and are authorized to modify and serve audio from.

The reference is [chuot/rdio-scanner](https://github.com/chuot/rdio-scanner), pinned at **`4f92b8648bb91d51149bb1603570422a075fcf4b`**. That revision exposes HTTP recorder-upload and admin routes; listener config, PIN authentication, call search and audio retrieval use WebSocket commands in the unmodified server. The companion endpoints below are new SWEEP endpoints, not pre-existing upstream routes. The upstream [API access policy](https://github.com/chuot/rdio-scanner/blob/4f92b8648bb91d51149bb1603570422a075fcf4b/API_ACCESS_POLICY.md) distinguishes HTTP APIs from restricted WebSocket access.

## License boundary

`rdio-http-v1.patch` contains original `server/sweep_http.go`, original server tests, and one registration call in the upstream server. These server additions are **GPL-3.0-or-later**, and are compiled with the GPL Rdio server. Preserve upstream copyright, attribution, license and source distribution obligations when distributing a modified server. The full GPL text is in [LICENSE](LICENSE).

SWEEP's independently written Android HTTP adapter communicates over this documented interface. No upstream Go or Angular implementation was copied into the Android app. An open-source software license does not supply rights to third-party feed content, server accounts or credentials.

## Apply and build

Requires Git, Go 1.26 or newer, and the Node.js/npm version supported by the pinned upstream Angular build. Start from a clean operator-owned checkout, not an installed production directory:

```sh
git clone https://github.com/chuot/rdio-scanner.git
cd rdio-scanner
git checkout 4f92b8648bb91d51149bb1603570422a075fcf4b
git apply --check /absolute/path/to/rdio-http-v1.patch
git apply /absolute/path/to/rdio-http-v1.patch
make webapp
cd server
go test ./...
go build -o ../rdio-scanner-sweep-http .
```

Use the normal upstream configuration/database and HTTPS deployment instructions. Launch the modified binary with `SWEEP_RDIO_HTTP=1` in its environment. Disabling that variable removes all companion routes on the next restart. Retain the original binary and database backup before an operator-controlled cutover. This package does not deploy, publish, alter a production server, or include any feed catalog or credentials.

Native SWEEP accepts HTTPS only. The companion accepts TLS, or a request originating from a loopback reverse proxy; it never trusts caller-supplied forwarded-protocol headers. Protect any proxy-to-server hop and do not expose that cleartext hop publicly. Apply appropriate HTTPS certificate configuration and request limits at the operator's reverse proxy. All responses use `Cache-Control: no-store`; no CORS permission is issued.

## HTTP version 1 contract

All requests are `POST` with `Content-Type: application/json`, an at-most 8 KiB body and **no query string**. The access code is the existing listener PIN, never an admin password. Send the PIN in the JSON body on each request. There is no bearer token, cookie, persistent HTTP login, automatic refresh or polling. Native SWEEP keeps its server address and PIN in memory only for the foreground browser and checks Internet-listening consent before every request.

* `POST /sweep-rdio/v1/config`: `{"pin":"listener-code"}`. Returns `{"apiVersion":1,"systems":[{"id":1,"label":"County","talkgroups":[{"id":11,"label":"Dispatch","name":"Fire"}]}]}`. Only authorized system/talkgroup metadata is included. At most 200 systems, 1,000 talkgroups per system and 5,000 talkgroups total are supported.
* `POST /sweep-rdio/v1/calls`: `{"pin":"listener-code","limit":50,"offset":0}`. Limit is 1–100; offset is 0–1,000,000. Returns `{"apiVersion":1,"count":1,"limit":50,"offset":0,"nextOffset":null,"calls":[{"id":101,"system":1,"talkgroup":11,"dateTime":"2026-10-12T18:05:00Z"}]}`. Calls sort by recorded timestamp descending, then call ID descending. `nextOffset` is null on the last page. Each page's count and records use one repeatable-read database snapshot. This is offset pagination over the current authorized archive; arrivals or pruning between requests can change page membership. Refresh is manual.
* `POST /sweep-rdio/v1/audio`: `{"pin":"listener-code","callId":101}`. Returns the selected call's raw audio bytes with an audio MIME type and `Content-Length`. Maximum audio is 8 MiB. Supported types are MPEG/MP3, AAC, MP4/M4A, WAV, Ogg and FLAC. There is no returned audio URL, filename, recording-state inference, stream relay or background playback loop.

Configuration and call-list responses are at most 1 MiB. The native client refuses redirects, compressed responses, malformed UTF-8, JSON nesting beyond 16 levels, inconsistent pages and unsupported audio headers. Connect/read timeouts are finite, transfers have a finite body-read deadline, and Stop cancels outstanding requests. Server responses have a 20-second deadline and at most eight simultaneous operations. An outer handler also sets a five-second connection read deadline before reading request bodies, because a context timeout alone cannot unblock a stalled body read. Connections without read-deadline support are refused with 503.

## Access and privacy behavior

Every request rechecks the current listener code, its expiration and system/talkgroup scope. An unrestricted upstream server accepts an empty PIN. A configured listener code with a positive `Access.Limit` is refused with HTTP 403: stateless requests do not hold an upstream listener lease, so accepting that code would bypass its connection limit. Use an operator-issued unlimited code or the upstream official client; this patch does not silently remove the limit.

All call-list and audio queries enforce the same allowed system/talkgroup references and exclude calls still in the `delayed` table. They also require the call timestamp plus its current effective configured delay to have elapsed: a positive talkgroup delay overrides its system delay, in minutes. This protects the interval between call insertion and delay-marker insertion, failed marker writes, and subsequent delay increases. A guessed unauthorized or delayed call ID returns 404. There is no admin endpoint or admin credential in this protocol. The companion never logs a PIN, returns a PIN, or places one in a URI. Failed credentials are limited to five attempts per peer address per 15-minute window, with a bounded peer table; budget checks and authentication are serialized so simultaneous requests cannot overshoot it. Forwarded-address headers are intentionally ignored; a shared local proxy should also apply its own per-user/IP abuse controls.

The server sees the listener's connection and requested calls. A reverse proxy or server administrator can observe request traffic; disable body logging for these routes and protect their credentials. SWEEP sends no RF observations, scan reports, device identifiers or phone GPS through these endpoints. Previously shared or operator-retained audio is outside the app's deletion controls.

Protocol errors contain only a fixed code and `apiVersion`: 400 malformed request/page; 401 invalid/expired code; 403 unsupported connection limit/scope or missing HTTPS; 404 unavailable call/endpoint; 413 payload/config/audio size; 415 content/audio type; 429 credential throttling; 500 storage failure; 503 busy/timeout/deadline support. A canceled connection can return an empty 503 or terminate before a response body is written. The Android adapter shows sanitized fixed messages and never includes an upstream body, PIN or request URL in an exception.

## Verification

The companion tests cover scoped metadata, scoped call/audio retrieval, delayed-marker and configured-delay exclusion, expiry and revocation checks, positive connection-limit refusal, sequential and concurrent incorrect-PIN throttling, page/payload/audio limits, unrestricted access, pagination and disabled-by-default registration. A real socket test fills all eight slots with stalled bodies and verifies bounded responses and a subsequent successful request. Native JVM tests cover consent before/after requests, POST-only credentials, finite manual pagination, cancellation versus permanent close, sanitized errors, unavailable upstream HTML, response bounds, malformed data and audio headers. Database fixtures use SQLite; PostgreSQL/MySQL deployment checks remain operator work. These tests establish protocol behavior with synthetic fixtures; they do not establish any particular public feed's availability or authorize access to it.

For a Go-only CI test without building the web client, a temporary `server/webapp/index.html` embed fixture is sufficient to compile the server test package. That fixture is **test-only**; production builds must use the real upstream `make webapp` output.

Run `python3 integrations/rdio-http/verify.py` from the SWEEP repository to fetch the pinned upstream into a temporary directory, check and apply the patch, run all Go tests, and compile a test-only server binary. The temporary source/binary is removed afterward. This is also a separate CI job; it does not deploy or start a server.
