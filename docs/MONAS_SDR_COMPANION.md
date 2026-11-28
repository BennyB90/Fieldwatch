# Monas SDR companion

**SWEEP + Radio → Scan → Monas SDR** shows a BLE census measured by an operator’s external receiver. **Preview synthetic census** works offline. Its plot and shared summary both carry the label **SAMPLE · SYNTHETIC MONAS CENSUS**. Live handset reception and thermal-drift performance await a physical bench trial.

## Connect a receiver

1. Run `monasd` in a BLE census or BLE packet view on the receiver. A field setup can use a Raspberry Pi, HackRF One and TCXO on a private Wi-Fi AP or USB-C Ethernet LAN. Keep the daemon on loopback behind an HTTPS proxy such as Caddy or Nginx. Restrict the proxy to the five GET routes below.
2. Enter the proxy’s HTTPS origin, including its port or path prefix if needed. Use a certificate trusted by Android, or obtain the leaf certificate’s SHA-256 public-key pin directly from the operator over a trusted channel. On the receiver, derive it from the known proxy certificate with the following command.

   ```sh
   openssl x509 -in receiver.pem -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256
   ```

3. For pinning, paste the 64 hex digits, colon-separated hex pairs, or `sha256/` followed by the Base64 digest. Pinning binds the entered host to the valid leaf public key and supports a self-signed LAN certificate. A key change requires a new operator-verified pin.
4. Enable Radio network access and tap **Authorize this server and connect**. Supply the optional bearer token if the proxy requires it. **Refresh** takes one snapshot. **Auto-poll (2 s)** waits two seconds between completed refreshes. Leaving the screen, disconnecting, an error or revoking consent ends polling. Reconnecting requires a fresh authorization and token.

## Readings and integrity

The MIT Kotlin client reads the public JSON protocol. The GPL-3.0-or-later daemon runs separately on the receiver. SWEEP ships none of its code. The phone requests `/v1/health`, `/v1/provenance`, `/v1/radios`, `/v1/net/census` and `/v1/net/ble?since=N`. Each refresh brackets the data reads with health checks. The most recent page retains up to 200 packets and the census displays up to 500 rows, with returned totals shown alongside them. [Public wire schema](https://github.com/musithang/sdrtop/blob/main/user_docs/api.md)

The banner reports integrity at the time of the last refresh. Feed loss makes counts lower bounds. A missing reading stays unavailable. **RELATIVE** uses the receiver’s own clock, **REFERENCED** uses an operator-stated reference and **TRACEABLE** denotes a reference standard. Unknown provenance or missing age for a calibrated reference disables comparison. A reference older than 900 seconds expires. A radio, view, sample-rate or integrity transition invalidates comparison for the connection.

Tap two rows or plotted points from one census with overlapping observation windows. Both axes show ±2 standard uncertainties. Rings mark rotating addresses. The CFO separation is the absolute offset difference divided by the uncertainties combined in quadrature. The optional two-dimensional distance is shown separately.

| CFO separation | Interpretation |
| --- | --- |
| ≥5σ | Strong negative exclusion consistent with distinct oscillators. A rapid thermal step can shift a single crystal. |
| 2σ to under 5σ | Inconclusive. Ordinary handling can shift the offset by +0.8 to +2.9 ppm. |
| Under 2σ | Indistinguishable offsets. Commercial crystals cluster around ±3.7 ppm. In one crowd model, the probability of a collision exceeds 50% at nine devices. |

Pair selection resets on refresh. The app never joins addresses across rotations, snapshots, room scans or Travel history. A census cannot establish device ownership, unwanted tracking or the absence of a transmitter. [SWEEP evidence limits](../SCOPE.md)

## Sharing and retention

The server receives the phone’s network address, read requests, sequence cursor and optional bearer header. Session measurements and credentials stay in memory until disconnect. **Share summary** creates an app-private temporary text file and opens Android’s share sheet. The default report includes counts, integrity, reference tier and aggregate packet/CRC/SNR statistics. Each share starts with **Include identifiers & numerical crystal offsets** switched off. Enabling it adds address rows, names, companies and their offset and modulation measurements with uncertainties. Reports omit server addresses, credentials and radio serials. The cache retains at most eight Monas share files and prunes files older than a day when preparing another share.
