# SWEEP Product Roadmap & Business Architecture

Updated November 27, 2026. This roadmap defines SWEEP's multi-phase product structure, target customer lanes, privacy and evidentiary boundaries, and planned hardware-companion architecture.

## 1. Strategic Thesis & Target Customers

Phone-only RF scanning is a useful entry wedge, but one-off room scans have low retention and Android already ships free built-in unknown tracker alerts. SWEEP's durable moat is **workflow + longitudinal baselines + honest multi-sensor evidence** backed by a verifiable zero-cloud offline foundation.

### Primary customer lanes (ship first)

1. **Consumer travelers & everyday privacy users** — Checking hotel rooms, rentals, personal vehicles, and bags with a clear, honest, phone-only workflow ($0 extra hardware required).
2. **Short-term rental hosts, boutique hotels, and property managers** — Running repeatable **pre-guest room turnovers** against a saved known-good space baseline and archiving **timestamped inspection PDF logs** as documented due diligence.

### Secondary expansion lanes (Layer 3–4)

3. **Executive protection, private investigators, corporate travel security, and fleet managers** — Multi-space baselines, vehicle sweeps, and optional zero-cloud USB-C / companion sensor kits feeding one unified audit report.

## 2. Evidentiary & Legal Guardrails

Every phase inherits the strict honesty standards of [SCOPE.md](../SCOPE.md):

- **No invented confidence percentages or guarantees.** SWEEP must never display unmeasured scores such as *"98% bug-free"*, *"Certified Safe Room"*, or *"No hidden cameras."*
- **Transparent coverage & completion summaries.** Peace of mind comes from showing clearly *what* was checked and *what* changed—for example: *"Wireless baseline: 0 new indicators · LAN check: not run · Physical checklist: 8/8 spots reviewed"*—alongside explicit capability limits (offline/SD-card recorders, wired cameras, and Wi-Fi-client cameras outside AP scanning).
- **Due-diligence framing for hosts.** Pre-guest turnover reports are labeled as **Timestamped Inspection / Turnover Logs**, documenting the wireless baseline comparison, optional authorized network review, and human-completed physical checklist items at a specific time—not a warranty that a room is free of surveillance.
- **Privacy & redaction by default.** Offline **SWEEP** (`app.fieldwatch.sweep`) keeps zero Internet permission. Shared and exported reports redact device names, MAC addresses, BSSIDs, and coordinates by default unless the user explicitly opts in.
- **MIT license hygiene.** Core SWEEP and all built-in adapters remain cleanly MIT-compatible; any GPL-3.0 external server or SDR daemon (`rdio-scanner`, `monasd`) stays isolated behind an explicit network boundary in **SWEEP + Radio** (`app.fieldwatch.sweep.radio`).

## 3. Four-Phase Architecture

### Phase 1 — Saved Spaces, Guided Sweep & Timestamped Inspection PDF *(Current Build Target)*

Build directly on SWEEP's existing Room Scan, [SweepComparisons](../app/src/main/java/app/fieldwatch/sweep/SweepComparisons.kt), and private storage architecture (phone-only, offline-first, works in both `sweep` and `sweepRadio`):

- **Saved Spaces (`Unit 4B`, `Master Suite`, `My Car`, `Home Office`, etc.):**
  - Persistent named spaces stored privately on device (`0700`/`0600` atomic storage, excluded from cloud backup and device transfer).
  - Each space stores a **Known-Good Baseline** (from a completed room scan) plus an editable **Expected / Familiar Devices** allowlist (owner router, smart TV, recurring neighbor APs, family devices) keyed strictly by exact `TRANSPORT:MAC` without silently merging rotated MACs.
  - Running or attaching a new completed room scan to a Saved Space automatically classifies findings into:
    - **New since baseline** (surfaced first — unexpected transmitters or indicators not in the space's baseline or expected list)
    - **Expected / Baseline matched** (collapsed cleanly so recurring neighbor routers do not drown out anomalies)
    - **Not reobserved from baseline** (explicitly noted as *not observed*, never claimed removed)
    - **Unresolved / incomplete coverage** (preserves SWEEP's existing coverage-honesty rules)
- **Guided Physical Inspection Checklist:**
  - Structured, space-attached physical inspection checklist covering common concealment locations that phone radios cannot see (smoke detectors, alarm clocks/radios, USB wall chargers/outlets, mirrors, HVAC vents, routers/modems, lamps/picture frames, and vehicle OBD/trunk/under-seat checks for vehicle spaces).
  - Each item tracks explicit human state (`Checked — nothing unusual`, `Needs follow-up` with optional note, or `Skipped / N/A`), keeping physical checks clearly separated from automated RF observations.
- **Timestamped Inspection / Turnover PDF Report:**
  - Native offline Android PDF export (`android.graphics.pdf.PdfDocument` — zero third-party cloud or heavy SDK bloat) generated on device and shared via `FileProvider` only on explicit user action.
  - Includes space name, inspection timestamp, duration, radio coverage summary, baseline delta counts (new / expected / not reobserved / unresolved), guided physical checklist completion summary, explicit scope/limitation disclaimer, and a **SHA-256 integrity digest** over the canonical report payload for tamper-evident host/PI archiving.
  - Redacts MAC addresses, BSSIDs, advertised names, and coordinates by default, with an explicit opt-in toggle when a host or investigator needs full identifiers for internal records.

### Phase 2 — Physical Handset Validation & Early Customer Trials

- Complete physical phone acceptance trials for permission flows, screen-off behavior, and battery consumption (`Travel → Battery test`), plus the hardware target rig screening matrix in [SCOPE.md](../SCOPE.md) and [PROCUREMENT.md](PROCUREMENT.md).
- Run real-world trials with 5–10 short-term rental hosts and frequent travelers to validate Saved Spaces UX, Turnover PDF utility, and Pro tier pricing.

### Phase 3 — Layer 3: SWEEP Sensor Hub *(Optional Zero-Cloud External Hardware)*

Introduce a modular `ExternalSensor` adapter architecture so supported third-party sensors plug directly into a **Saved Space** session and **Inspection PDF** without forcing users to install cloud-connected vendor apps:

1. **USB-C Thermal Camera — Topdon TC001 / TC001 Plus (Open UVC / MIT SDK):**
   - **Why Topdon first:** Both the **TC001** (256×192 IR, 25 Hz, battery-free USB-C) and **TC001 Plus** (adds a 1 MP visible-light lens for dual-image fusion) stream over standard USB Video Class (UVC), and Topdon publishes its official `TopdonTechnology/Thermal` SDK resources on GitHub under the **MIT License**—avoiding proprietary closed-SDK approval gates and royalties.
   - **Recommended hardware for prototyping & field use:** Grab **Topdon TC001 Plus** when visual-edge context is needed to pinpoint exact warm spots inside vents/outlets/clocks, or standard **Topdon TC001** as the lowest-cost, most widely documented pure-UVC prototyping target.
   - **SWEEP integration value:** Zero-Internet offline thermal capture focused on subtle ΔT electronics heat spots, logged directly against a Saved Space physical checklist item and embedded into the Turnover PDF.
   - **FLIR ONE** remains deferred unless enterprise customers specifically require it, due to Teledyne FLIR's closed commercial SDK vetting/EULA and MIT incompatibility.
2. **Wideband Pocket Spectrum Analyzer — `tinySA Ultra` (~$140, USB-C Serial):**
   - Self-powered (internal battery), open USB-CDC serial command protocol (clean MIT Kotlin implementation), covering `100 kHz – 5.3+ GHz` to detect wideband RF spikes (cellular/analog/non-Wi-Fi/BLE transmitters) outside Android's radio APIs.
3. **Optional RF Metrology Companion — `monasd` + HackRF One + TCXO (`SWEEP + Radio` only):**
   - Clean-room MIT HTTPS polling client in `SWEEP + Radio` connecting to an operator-hosted `monasd` appliance (mirroring `RdioHttpClient` GPL isolation) for pro-grade BLE channel, SNR, and `>5σ` crystal-offset negative exclusion across rotating MACs. Raw crystal floats remain redacted by default in shared reports.

### Phase 4 — Layer 4: Curated SWEEP Field Kit *(Post-PMF Hardware + Software Bundle)*

Once Phases 1–3 prove steady subscriber retention, evaluate a pre-tested turnkey kit (protective case + verified USB-C thermal camera + `tinySA Ultra` + SWEEP Pro license) for boutique hotels, property management teams, and corporate security.

## 4. Planned Commercial Packaging (Post-Validation Hypothesis)

- **SWEEP Free (Offline):** Unlimited on-demand Room Scans, Travel Mode, Drone Remote ID, Guided Physical Checklist, and `1` Saved Space.
- **SWEEP Personal Pro:** Unlimited Saved Spaces (Home, Car, Travel), full baseline history, and timestamped Inspection PDF exports.
- **SWEEP Host / Team:** Multi-property Space management, branded Pre-Guest Turnover PDF Logs with SHA-256 archive hashes, and (in Phase 3) Sensor Hub support.
