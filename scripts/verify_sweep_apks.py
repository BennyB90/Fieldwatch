#!/usr/bin/env python3
"""Verify the shipped SWEEP APKs (and optional Play AABs), including merged manifests/resources.

Requires Android SDK command-line tools, build-tools 35.0.0+ (36.0.0+ preferred), and JAVA_HOME.
Works on Windows, macOS, and Linux. Checks artifact properties; does not certify RF performance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ANDROID = "{http://schemas.android.com/apk/res/android}"
DOMAINS = {"root", "file", "database", "sharedpref", "external", "device_root",
           "device_file", "device_database", "device_sharedpref"}
BASE_PERMISSIONS = {
    "ACCESS_WIFI_STATE", "CHANGE_WIFI_STATE", "ACCESS_NETWORK_STATE",
    "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "BLUETOOTH", "BLUETOOTH_ADMIN",
    "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "NEARBY_WIFI_DEVICES", "POST_NOTIFICATIONS",
    "VIBRATE", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_LOCATION",
    "FOREGROUND_SERVICE_CONNECTED_DEVICE",
}
RADIO_PERMISSIONS = {"INTERNET", "CHANGE_WIFI_MULTICAST_STATE", "WAKE_LOCK",
                     "FOREGROUND_SERVICE_MEDIA_PLAYBACK"}
DEPENDENCY_INFO_BLOCK_ID = 0x504B4453  # Google Play encrypted SDK dependency block ("SDKP")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(*args):
    return subprocess.run(
        [str(arg) for arg in args],
        check=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ).stdout


def resolve_executable(directory: Path, stem: str) -> Path:
    suffixes = (".bat", ".cmd", ".exe", "") if os.name == "nt" else ("",)
    for suffix in suffixes:
        candidate = directory / f"{stem}{suffix}"
        if candidate.is_file():
            return candidate
    raise ValueError(f"Missing SDK tool '{stem}' in {directory}")


def resolve_sdk_from_local_properties(repo_root: Path) -> str | None:
    prop_file = repo_root / "local.properties"
    if not prop_file.is_file():
        return None
    for raw in prop_file.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() == "sdk.dir":
            unescaped = value.strip().replace(r"\:", ":").replace(r"\\", "\\")
            if unescaped:
                return unescaped
    return None


def version_tuple(text: str) -> tuple[int, ...]:
    parts = re.findall(r"\d+", text)
    return tuple(int(p) for p in parts) if parts else (0,)


def resolve_apkanalyzer(sdk: Path) -> Path:
    cmdline_root = sdk / "cmdline-tools"
    require(cmdline_root.is_dir(), f"Install Android SDK cmdline-tools under {sdk}")
    candidates = []
    latest = cmdline_root / "latest" / "bin"
    if latest.is_dir():
        candidates.append(((999999,), latest))
    for child in cmdline_root.iterdir():
        bin_dir = child / "bin"
        if child.name != "latest" and bin_dir.is_dir():
            candidates.append((version_tuple(child.name), bin_dir))
    for _, bin_dir in sorted(candidates, key=lambda item: item[0], reverse=True):
        try:
            return resolve_executable(bin_dir, "apkanalyzer")
        except ValueError:
            continue
    raise ValueError("Install Android SDK cmdline-tools;latest (apkanalyzer not found)")


def resolve_build_tools(sdk: Path, requested: str | None) -> Path:
    bt_root = sdk / "build-tools"
    require(bt_root.is_dir(), f"Install Android SDK build-tools under {sdk}")
    if requested:
        explicit = bt_root / requested
        if explicit.is_dir():
            return explicit
    installed = [
        (version_tuple(child.name), child)
        for child in bt_root.iterdir()
        if child.is_dir() and "-" not in child.name and version_tuple(child.name) >= (35, 0, 0)
    ]
    require(bool(installed), "Install Android SDK build-tools 35.0.0+ (36.0.0+ preferred)")
    return sorted(installed, key=lambda item: item[0], reverse=True)[0][1]


def excludes_all(element):
    require(element is not None, "Missing backup mode")
    require(not element.findall("include"), "Backup must not contain inclusions")
    exclusions = {(node.get("domain"), node.get("path")) for node in element.findall("exclude")}
    require({(domain, ".") for domain in DOMAINS} <= exclusions,
            "Every backup storage domain must be excluded")


def check_manifest(root, radio):
    package = "app.fieldwatch.sweep" + (".radio" if radio else "")
    require(root.get("package") == package, "Wrong package identity")
    app = root.find("application")
    require(app is not None, "Missing application")
    require(app.get(ANDROID + "debuggable", "false") == "false", "Debuggable APK")
    require(app.get(ANDROID + "testOnly", "false") == "false", "Test-only APK")
    require(app.get(ANDROID + "allowBackup") == "false", "Backup must be disabled")
    expected_class = "app.fieldwatch.sweep." + ("radio.ScannerRadioApplication" if radio else "SweepApplication")
    require(app.get(ANDROID + "name") == expected_class, "Wrong Application class")
    sdk = root.find("uses-sdk")
    require(sdk is not None and sdk.get(ANDROID + "minSdkVersion") == "29", "Android 10 support changed")
    require(int(sdk.get(ANDROID + "targetSdkVersion", "0")) >= 36,
            "Target SDK must be 36+ for Google Play submission")
    permissions = {node.get(ANDROID + "name") for node in root if node.tag.startswith("uses-permission")}
    expected = {"android.permission." + item for item in BASE_PERMISSIONS | (RADIO_PERMISSIONS if radio else set())}
    expected.add(package + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    require(permissions == expected, "Permission set changed: " + str(sorted(permissions ^ expected)))
    require(root.find("queries") is None, "Unexpected package-visibility <queries> declaration in SWEEP")
    if radio:
        require(app.get(ANDROID + "usesCleartextTraffic") == "false", "Cleartext network traffic enabled")
    launcher = "app.fieldwatch.sweep.SweepActivity"
    component_names = set()
    for component in app:
        if component.tag not in {"activity", "activity-alias", "service", "receiver", "provider"}:
            continue
        name = component.get(ANDROID + "name")
        component_names.add(name)
        exported = component.get(ANDROID + "exported")
        if name == launcher and component.tag == "activity":
            require(exported == "true", "Launcher must be exported")
        elif name == "androidx.profileinstaller.ProfileInstallReceiver" and component.tag == "receiver":
            require(component.get(ANDROID + "permission") == "android.permission.DUMP",
                    "Profile installer receiver lost its privileged permission")
        else:
            require(exported == "false", f"Unexpected exported/implicit component: {name}")
    require(launcher in component_names, "Missing launcher")
    require(("app.fieldwatch.sweep.network.SweepNetworkActivity" in component_names) == radio,
            "Network screen in wrong variant")
    require("app.fieldwatch.MainActivity" not in component_names and "app.fieldwatch.radio.ScanService" not in component_names,
            "Upstream entry points leaked into SWEEP")
    require(not any(node.get(ANDROID + "name") == "android.net.VpnService" for node in root.iter()),
            "Unexpected VPN service")
    return app


def native_alignment(archive_path: Path, prefixes=("lib/arm64-v8a/", "lib/x86_64/", "base/lib/arm64-v8a/", "base/lib/x86_64/")):
    libraries = []
    with ZipFile(archive_path) as archive:
        for name in archive.namelist():
            if not name.startswith(prefixes) or not name.endswith(".so"):
                continue
            data = archive.read(name)
            require(data[:5] == b"\x7fELF\x02", f"Invalid 64-bit ELF: {name}")
            require(data[5] in (1, 2), f"Invalid ELF byte order: {name}")
            order = "<" if data[5] == 1 else ">"
            header = struct.unpack_from(order + "HHIQQQIHHHHHH", data, 16)
            offset, size, count = header[4], header[8], header[9]
            alignments = []
            for index in range(count):
                segment = struct.unpack_from(order + "IIQQQQQQ", data, offset + index * size)
                if segment[0] == 1:
                    require(segment[-1] >= 16384 and segment[2] % 16384 == segment[3] % 16384,
                            f"Native load segment lacks 16 KB alignment: {name}")
                    alignments.append(segment[-1])
            require(bool(alignments), f"No load segments: {name}")
            libraries.append({"path": name, "loadAlignments": alignments})
    return libraries


def has_apk_signing_block_id(apk: Path, target_id: int) -> bool:
    data = apk.read_bytes()
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0 or eocd + 20 > len(data):
        return False
    cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
    if cd_offset < 32 or data[cd_offset - 16:cd_offset] != b"APK Sig Block 42":
        return False
    block_size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
    start = cd_offset - 8 - block_size
    if start < 0 or struct.unpack_from("<Q", data, start)[0] != block_size:
        return False
    pos = start + 8
    end = cd_offset - 24
    while pos + 12 <= end:
        pair_len = struct.unpack_from("<Q", data, pos)[0]
        if pair_len < 4 or pos + 8 + pair_len > end:
            break
        pair_id = struct.unpack_from("<I", data, pos + 8)[0]
        if pair_id == target_id:
            return True
        pos += 8 + pair_len
    return False


def verify(apk: Path, radio: bool, analyzer: Path, build_tools: Path):
    require(apk.is_file(), f"Missing built APK: {apk}")
    aapt = resolve_executable(build_tools, "aapt")
    apksigner = resolve_executable(build_tools, "apksigner")
    zipalign = resolve_executable(build_tools, "zipalign")

    root = ET.fromstring(run(analyzer, "manifest", "print", apk))
    app = check_manifest(root, radio)
    resources = run(aapt, "dump", "--values", "resources", apk)

    def resource_xml(attribute):
        reference = app.get(ANDROID + attribute, "")
        match = re.fullmatch(r"@(?:ref/)?(0x[0-9a-fA-F]+)", reference)
        require(match is not None, f"Missing compiled {attribute} resource")
        paths = re.findall(r"^\s*resource " + re.escape(match[1]) +
                           r" [^\n]+\n\s*\(string8?\) \"([^\"]+)\"", resources, re.MULTILINE)
        require(bool(paths), f"Cannot resolve {attribute} resource")
        return [ET.fromstring(run(analyzer, "resources", "xml", "--file", path, apk)) for path in set(paths)]

    for legacy in resource_xml("fullBackupContent"):
        require(legacy.tag == "full-backup-content", "Wrong legacy backup schema")
        excludes_all(legacy)
    for rules in resource_xml("dataExtractionRules"):
        require(rules.tag == "data-extraction-rules", "Wrong extraction schema")
        excludes_all(rules.find("cloud-backup"))
        excludes_all(rules.find("device-transfer"))

    cert_info = run(apksigner, "verify", "--verbose", "--print-certs", apk)
    release_signed = "CN=Android Debug" not in cert_info
    run(zipalign, "-c", "-P", "16", "4", apk)
    require(not has_apk_signing_block_id(apk, DEPENDENCY_INFO_BLOCK_ID),
            "Sideload APK unexpectedly contains Google Play encrypted dependency block")
    libraries = native_alignment(apk)
    return {
        "path": str(apk), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "bytes": apk.stat().st_size, "package": root.get("package"),
        "versionCode": root.get(ANDROID + "versionCode"),
        "versionName": root.get(ANDROID + "versionName"),
        "targetSdk": root.find("uses-sdk").get(ANDROID + "targetSdkVersion"),
        "signatureVerified": True, "releaseSigned": release_signed,
        "debuggable": False, "privacyManifestVerified": True,
        "cloudAndTransferExclusionsVerified": True, "zipAlignment16KB": True,
        "playDependencyBlockExcluded": True,
        "nativeLibraries64Bit": libraries,
    }


def verify_bundle(aab: Path, radio: bool):
    require(aab.is_file(), f"Missing built Android App Bundle: {aab}")
    package = "app.fieldwatch.sweep" + (".radio" if radio else "")
    with ZipFile(aab) as archive:
        names = set(archive.namelist())
        require("BundleConfig.pb" in names, f"Missing BundleConfig.pb in {aab}")
        require("base/manifest/AndroidManifest.xml" in names, f"Missing base manifest in {aab}")
        require(any(name.startswith("base/dex/") and name.endswith(".dex") for name in names),
                f"Missing compiled DEX in {aab}")
        manifest_bytes = archive.read("base/manifest/AndroidManifest.xml")
        require(package.encode("utf-8") in manifest_bytes, f"Wrong package identity in {aab}")
    libraries = native_alignment(aab)
    return {
        "path": str(aab),
        "sha256": hashlib.sha256(aab.read_bytes()).hexdigest(),
        "bytes": aab.stat().st_size,
        "package": package,
        "nativeLibraries64Bit": libraries,
    }


def main():
    repo_root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME")
                        or os.environ.get("ANDROID_SDK_ROOT")
                        or resolve_sdk_from_local_properties(repo_root))
    parser.add_argument("--build-tools", default=None)
    parser.add_argument("--apk-dir", type=Path, default=Path("app/build/outputs/apk"))
    parser.add_argument("--bundle-dir", type=Path, default=None)
    args = parser.parse_args()
    require(bool(args.sdk), "Set ANDROID_HOME, ANDROID_SDK_ROOT, local.properties sdk.dir, or --sdk")
    sdk = Path(args.sdk)
    analyzer = resolve_apkanalyzer(sdk)
    build_tools = resolve_build_tools(sdk, args.build_tools)
    results = {variant: verify(args.apk_dir / variant / f"app-{variant}.apk", radio,
                               analyzer, build_tools)
               for variant, radio in [("sweep", False), ("sweepRadio", True)]}
    require(results["sweep"]["versionCode"] == results["sweepRadio"]["versionCode"], "Variant versions differ")
    output = {
        "installers": results,
        "buildTools": build_tools.name,
        "physicalPerformanceVerified": False,
        "productionReleaseVerified": bool(
            results["sweep"]["releaseSigned"] and results["sweepRadio"]["releaseSigned"] and args.bundle_dir
        ),
    }
    if args.bundle_dir is not None:
        output["bundles"] = {
            variant: verify_bundle(args.bundle_dir / variant / f"app-{variant}.aab", radio)
            for variant, radio in [("sweep", False), ("sweepRadio", True)]
        }
    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()
