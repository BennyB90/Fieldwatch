# Cloudflare Pages Static Distribution ($0/month)

Because SWEEP performs all RF scanning and session storage locally on each user's Android phone, **0 backend servers are required** for scanning. Cloudflare Pages' free tier serves static HTML/CSS, the privacy policy (`privacy.html`), checksums, and direct APK downloads (`< 25 MiB` per file, unlimited static bandwidth on the free plan).

---

## Directory Layout (`site/`)

- [`site/index.html`](../site/index.html) — Mobile-responsive overview, capability limits, sideload instructions, and live SHA-256 download cards for **SWEEP** (`app-sweep.apk`) and **SWEEP + Radio** (`app-sweepRadio.apk`).
- [`site/privacy.html`](../site/privacy.html) — Standalone Privacy Policy suitable for alpha testers and Google Play Console's Privacy Policy URL requirement.
- [`site/styles.css`](../site/styles.css) — Self-contained light/dark stylesheet (zero external fonts, CDNs, cookies, or analytics).
- [`site/_headers`](../site/_headers) — Cloudflare Pages security headers (`Content-Security-Policy`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, and Android package `Content-Type` headers).
- [`scripts/stage_pages_site.py`](../scripts/stage_pages_site.py) — Copies the verified APKs into `site/downloads/`, writes `SHA256SUMS.txt` and `manifest.json`, and stamps the exact version codes, byte sizes, and SHA-256 hashes into `site/index.html`.

*(Binary `site/downloads/*.apk` files are git-ignored so repository history stays lean; stage them right before deploying.)*

---

## Build, Verify & Deploy

### 1. Assemble, verify, and stage
On Windows PowerShell:
```powershell
.\gradlew.bat :app:assembleSweep :app:assembleSweepRadio
python scripts/verify_sweep_apks.py > sweep-apk-verification.json
python scripts/stage_pages_site.py --verification-json sweep-apk-verification.json
```

On Linux / macOS:
```sh
./gradlew :app:assembleSweep :app:assembleSweepRadio
python3 scripts/verify_sweep_apks.py > sweep-apk-verification.json
python3 scripts/stage_pages_site.py --verification-json sweep-apk-verification.json
```

### 2. Deploy to Cloudflare Pages
Using Wrangler CLI (one-time `npx wrangler login`):
```sh
npx wrangler pages project create sweep --production-branch main
npx wrangler pages deploy site --project-name sweep
```
Or upload the staged `site/` folder directly in the Cloudflare Dashboard under **Workers & Pages → Create → Pages → Upload assets**.
