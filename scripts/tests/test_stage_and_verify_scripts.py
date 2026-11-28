import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import stage_pages_site as stager  # noqa: E402
import verify_sweep_apks as verifier  # noqa: E402


def build_minimal_elf(is_64: bool, align: int, offset: int = 0, vaddr: int = 0) -> bytes:
    if is_64:
        ehsize, phentsize = 64, 56
        e_ident = b"\x7fELF" + bytes([2, 1, 1]) + b"\x00" * 9
        header = struct.pack(
            "<HHIQQQIHHHHHH",
            3, 183, 1, 0, ehsize, 0, 0, ehsize, phentsize, 1, 0, 0, 0,
        )
        phdr = struct.pack(
            "<IIQQQQQQ",
            1, 5, offset, vaddr, vaddr, 128, 128, align,
        )
    else:
        ehsize, phentsize = 52, 32
        e_ident = b"\x7fELF" + bytes([1, 1, 1]) + b"\x00" * 9
        header = struct.pack(
            "<HHIIIIIHHHHHH",
            3, 40, 1, 0, ehsize, 0, 0, ehsize, phentsize, 1, 0, 0, 0,
        )
        phdr = struct.pack(
            "<IIIIIIII",
            1, offset, vaddr, vaddr, 128, 128, 5, align,
        )
    return e_ident + header + phdr


def build_manifest_xml(radio: bool = False, include_camera_perm: bool = True, camera_required: str = "false") -> ET.Element:
    pkg = "app.fieldwatch.sweep.radio" if radio else "app.fieldwatch.sweep"
    app_cls = "app.fieldwatch.sweep.radio.ScannerRadioApplication" if radio else "app.fieldwatch.sweep.SweepApplication"
    perms = set(verifier.BASE_PERMISSIONS) | (verifier.RADIO_PERMISSIONS if radio else set())
    if not include_camera_perm:
        perms.discard("CAMERA")
    perm_tags = "\n".join(f'<uses-permission android:name="android.permission.{p}" />' for p in sorted(perms))
    perm_tags += f'\n<uses-permission android:name="{pkg}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />'
    net_act = (
        '<activity android:name="app.fieldwatch.sweep.network.SweepNetworkActivity" android:exported="false" />'
        if radio else ""
    )
    cleartext = 'android:usesCleartextTraffic="false"' if radio else ""
    xml = f"""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{pkg}">
      <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36" />
      {perm_tags}
      <uses-feature android:name="android.hardware.camera" android:required="{camera_required}" />
      <uses-feature android:name="android.hardware.usb.host" android:required="false" />
      <application android:name="{app_cls}" android:allowBackup="false" android:debuggable="false" {cleartext}>
        <activity android:name="app.fieldwatch.sweep.SweepActivity" android:exported="true" />
        <activity android:name="app.fieldwatch.sweep.spaces.SweepSpacesActivity" android:exported="false" />
        <activity android:name="app.fieldwatch.sweep.thermal.SweepThermalActivity" android:exported="false" />
        <activity android:name="app.fieldwatch.sweep.spectrum.SweepSpectrumActivity" android:exported="false" />
        {net_act}
      </application>
    </manifest>"""
    return ET.fromstring(xml)


class StageAndVerifyScriptTests(unittest.TestCase):
    def test_native_alignment_accepts_16kb_four_abi_bridge_and_rejects_4kb(self):
        with tempfile.TemporaryDirectory() as tmp:
            ok_zip = Path(tmp) / "ok.apk"
            with ZipFile(ok_zip, "w") as zf:
                for abi, is_64 in (("arm64-v8a", True), ("armeabi-v7a", False), ("x86", False), ("x86_64", True)):
                    zf.writestr(f"lib/{abi}/libsweep_uvc.so", build_minimal_elf(is_64=is_64, align=16384))
            libs64, bridges = verifier.native_alignment(ok_zip, "lib")
            self.assertEqual(2, len(libs64))
            self.assertEqual(4, len(bridges))

            bad_zip = Path(tmp) / "bad.apk"
            with ZipFile(bad_zip, "w") as zf:
                for abi, is_64 in (("arm64-v8a", True), ("armeabi-v7a", False), ("x86", False), ("x86_64", True)):
                    align = 4096 if abi == "arm64-v8a" else 16384
                    zf.writestr(f"lib/{abi}/libsweep_uvc.so", build_minimal_elf(is_64=is_64, align=align))
            with self.assertRaisesRegex(ValueError, "16 KB alignment"):
                verifier.native_alignment(bad_zip, "lib")

    def test_check_manifest_enforces_optional_camera_and_zero_internet_in_offline_sweep(self):
        verifier.check_manifest(build_manifest_xml(radio=False), radio=False)
        verifier.check_manifest(build_manifest_xml(radio=True), radio=True)
        with self.assertRaisesRegex(ValueError, "Permission set changed"):
            verifier.check_manifest(build_manifest_xml(radio=False, include_camera_perm=False), radio=False)
        with self.assertRaisesRegex(ValueError, "android.hardware.camera feature must be declared optional"):
            verifier.check_manifest(build_manifest_xml(radio=False, camera_required="true"), radio=False)

    def test_check_manifest_requires_spectrum_activity_in_both_variants(self):
        for radio in (False, True):
            root = build_manifest_xml(radio=radio)
            app = root.find("application")
            component = next(node for node in app.findall("activity")
                             if node.get(verifier.ANDROID + "name").endswith(".SweepSpectrumActivity"))
            app.remove(component)
            with self.assertRaisesRegex(ValueError, "Missing wideband spectrum activity"):
                verifier.check_manifest(root, radio=radio)

    def test_has_apk_signing_block_id_detects_play_dependency_block(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "sample.apk"
            pair_payload = struct.pack("<I", verifier.DEPENDENCY_INFO_BLOCK_ID) + b"meta"
            pair = struct.pack("<Q", len(pair_payload)) + pair_payload
            block_size = len(pair) + 24
            sig_block = (
                struct.pack("<Q", block_size)
                + pair
                + struct.pack("<Q", block_size)
                + b"APK Sig Block 42"
            )
            prefix = b"PK\x03\x04dummy"
            cd_offset = len(prefix) + len(sig_block)
            eocd = b"PK\x05\x06" + b"\x00" * 12 + struct.pack("<I", cd_offset) + b"\x00\x00"
            apk.write_bytes(prefix + sig_block + eocd)
            self.assertTrue(verifier.has_apk_signing_block_id(apk, verifier.DEPENDENCY_INFO_BLOCK_ID))
            self.assertFalse(verifier.has_apk_signing_block_id(apk, 0x12345678))

    def test_stage_pages_site_updates_hashes_and_rejects_digest_mismatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            site_dir = root / "site"
            apk_dir = root / "apk"
            (site_dir).mkdir()
            (apk_dir / "sweep").mkdir(parents=True)
            (apk_dir / "sweepRadio").mkdir(parents=True)
            sweep_bytes = b"sweep-apk-binary"
            radio_bytes = b"sweep-radio-apk-binary"
            (apk_dir / "sweep" / "app-sweep.apk").write_bytes(sweep_bytes)
            (apk_dir / "sweepRadio" / "app-sweepRadio.apk").write_bytes(radio_bytes)
            (site_dir / "index.html").write_text(
                '<span id="sweep-version">old</span><span id="sweep-size">old</span><code id="sweep-sha256">old</code>'
                '<span id="radio-version">old</span><span id="radio-size">old</span><code id="radio-sha256">old</code>',
                encoding="utf-8",
            )
            ver_json = root / "verified.json"
            ver_json.write_text(
                "\ufeff" + json.dumps({
                    "installers": {
                        "sweep": {
                            "package": "app.fieldwatch.sweep",
                            "versionName": "1.1.26-sweep-alpha.6",
                            "versionCode": "40",
                            "targetSdk": "36",
                            "sha256": stager.sha256_file(apk_dir / "sweep" / "app-sweep.apk"),
                        },
                        "sweepRadio": {
                            "package": "app.fieldwatch.sweep.radio",
                            "versionName": "1.1.26-sweep-radio-alpha.6",
                            "versionCode": "40",
                            "targetSdk": "36",
                            "sha256": stager.sha256_file(apk_dir / "sweepRadio" / "app-sweepRadio.apk"),
                        },
                    }
                }),
                encoding="utf-8",
            )
            manifest = stager.stage_site(site_dir, apk_dir, verification_json=ver_json, copy_apks=False)
            self.assertFalse((site_dir / "downloads" / "app-sweep.apk").exists())
            self.assertEqual("40", manifest["installers"]["sweep"]["versionCode"])
            updated_html = (site_dir / "index.html").read_text(encoding="utf-8")
            self.assertIn("1.1.26-sweep-alpha.6 (code 40)", updated_html)
            self.assertIn(manifest["installers"]["sweep"]["sha256"], updated_html)

            bad_json = root / "bad.json"
            bad_json.write_text(json.dumps({"installers": {"sweep": {"sha256": "0" * 64}}}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                stager.stage_site(site_dir, apk_dir, verification_json=bad_json, copy_apks=False)


if __name__ == "__main__":
    unittest.main()
