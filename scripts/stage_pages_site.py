#!/usr/bin/env python3
"""Stage verified SWEEP APKs, SHA256SUMS.txt, manifest.json, and live hashes into site/.

Cloudflare Pages free tier allows files up to 25 MiB each (app-sweep.apk is ~9.8 MB and
app-sweepRadio.apk is ~13.7 MB). Run after assembling and verifying both APKs.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
from pathlib import Path
import re
import shutil

MAX_CF_PAGES_FILE_BYTES = 25 * 1024 * 1024


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(65536):
            digest.update(chunk)
    return digest.hexdigest()


def format_mb(size_bytes: int) -> str:
    return f"{size_bytes / (1024 * 1024):.2f} MB ({size_bytes:,} bytes)"


def update_span_or_code(page: str, element_id: str, value: str) -> str:
    escaped = html.escape(value)
    pattern = re.compile(rf'(<(?:span|code)\b[^>]*\bid="{re.escape(element_id)}"[^>]*>)(.*?)(</(?:span|code)>)', re.DOTALL)
    updated, count = pattern.subn(rf"\g<1>{escaped}\g<3>", page, count=1)
    if count != 1:
        raise ValueError(f"Missing #{element_id} placeholder in site/index.html")
    return updated


def stage_site(
    site_dir: Path,
    apk_dir: Path,
    verification_json: Path | None = None,
    copy_apks: bool = True,
) -> dict:
    downloads = site_dir / "downloads"
    downloads.mkdir(parents=True, exist_ok=True)

    verified_meta = {}
    if verification_json and verification_json.is_file():
        verified_meta = json.loads(verification_json.read_text(encoding="utf-8-sig")).get("installers", {})

    artifacts = {}
    checksum_lines = []
    for variant, filename in (("sweep", "app-sweep.apk"), ("sweepRadio", "app-sweepRadio.apk")):
        src = apk_dir / variant / filename
        if not src.is_file():
            raise FileNotFoundError(f"Missing built APK: {src}")
        size = src.stat().st_size
        if size > MAX_CF_PAGES_FILE_BYTES:
            raise ValueError(f"{filename} ({size} bytes) exceeds Cloudflare Pages' 25 MiB per-file limit")
        digest = sha256_file(src)
        meta = verified_meta.get(variant, {})
        if meta.get("sha256") and meta["sha256"] != digest:
            raise ValueError(f"SHA-256 mismatch between {src} and verification JSON")
        if copy_apks:
            shutil.copy2(src, downloads / filename)
        checksum_lines.append(f"{digest}  {filename}")
        artifacts[variant] = {
            "filename": filename,
            "url": f"/downloads/{filename}",
            "package": meta.get("package", "app.fieldwatch.sweep.radio" if variant == "sweepRadio" else "app.fieldwatch.sweep"),
            "versionName": meta.get("versionName", f"1.1.26-{'sweep-radio' if variant == 'sweepRadio' else 'sweep'}-alpha.5"),
            "versionCode": meta.get("versionCode", "39"),
            "targetSdk": meta.get("targetSdk", "36"),
            "bytes": size,
            "sha256": digest,
        }

    (downloads / "SHA256SUMS.txt").write_text("\n".join(checksum_lines) + "\n", encoding="utf-8")
    manifest = {"schemaVersion": 1, "installers": artifacts}
    (downloads / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    index_path = site_dir / "index.html"
    index_html = index_path.read_text(encoding="utf-8")
    sweep_info = artifacts["sweep"]
    radio_info = artifacts["sweepRadio"]
    index_html = update_span_or_code(index_html, "sweep-version", f"{sweep_info['versionName']} (code {sweep_info['versionCode']})")
    index_html = update_span_or_code(index_html, "sweep-size", format_mb(sweep_info["bytes"]))
    index_html = update_span_or_code(index_html, "sweep-sha256", sweep_info["sha256"])
    index_html = update_span_or_code(index_html, "radio-version", f"{radio_info['versionName']} (code {radio_info['versionCode']})")
    index_html = update_span_or_code(index_html, "radio-size", format_mb(radio_info["bytes"]))
    index_html = update_span_or_code(index_html, "radio-sha256", radio_info["sha256"])
    index_path.write_text(index_html, encoding="utf-8")
    return manifest


def main(argv: list[str] | None = None) -> int:
    repo_root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--site-dir", type=Path, default=repo_root / "site")
    parser.add_argument("--apk-dir", type=Path, default=repo_root / "app" / "build" / "outputs" / "apk")
    parser.add_argument("--verification-json", type=Path, default=None)
    parser.add_argument("--no-copy-apks", action="store_true", help="Update hashes and metadata without copying binary APKs into site/downloads/")
    args = parser.parse_args(argv)
    manifest = stage_site(
        site_dir=args.site_dir,
        apk_dir=args.apk_dir,
        verification_json=args.verification_json,
        copy_apks=not args.no_copy_apks,
    )
    print(json.dumps(manifest, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
