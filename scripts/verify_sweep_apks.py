#!/usr/bin/env python3
"""Verify the shipped alpha APKs, including merged dependency manifests/resources.

Requires Android SDK command-line tools, build-tools 35.0.0+, and JAVA_HOME.
This checks artifact properties; it does not certify runtime or RF performance.
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


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(*args):
    return subprocess.run([str(arg) for arg in args], check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


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
    require(int(sdk.get(ANDROID + "targetSdkVersion", "0")) >= 35, "Unexpected target SDK regression")
    permissions = {node.get(ANDROID + "name") for node in root if node.tag.startswith("uses-permission")}
    expected = {"android.permission." + item for item in BASE_PERMISSIONS | (RADIO_PERMISSIONS if radio else set())}
    expected.add(package + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    require(permissions == expected, "Permission set changed: " + str(sorted(permissions ^ expected)))
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


def native_alignment(apk):
    libraries = []
    with ZipFile(apk) as archive:
        for name in archive.namelist():
            if not name.startswith(("lib/arm64-v8a/", "lib/x86_64/")) or not name.endswith(".so"):
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


def verify(apk, radio, analyzer, build_tools):
    root = ET.fromstring(run(analyzer, "manifest", "print", apk))
    app = check_manifest(root, radio)
    resources = run(build_tools / "aapt", "dump", "--values", "resources", apk)

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
    run(build_tools / "apksigner", "verify", "--verbose", apk)
    run(build_tools / "zipalign", "-c", "-P", "16", "4", apk)
    libraries = native_alignment(apk)
    return {
        "path": str(apk), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "bytes": apk.stat().st_size, "package": root.get("package"),
        "versionCode": root.get(ANDROID + "versionCode"),
        "versionName": root.get(ANDROID + "versionName"),
        "targetSdk": root.find("uses-sdk").get(ANDROID + "targetSdkVersion"),
        "signatureVerified": True, "debuggable": False, "privacyManifestVerified": True,
        "cloudAndTransferExclusionsVerified": True, "zipAlignment16KB": True,
        "nativeLibraries64Bit": libraries,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--build-tools", default="35.0.0")
    parser.add_argument("--apk-dir", type=Path, default=Path("app/build/outputs/apk"))
    args = parser.parse_args()
    require(bool(args.sdk), "Set ANDROID_HOME or --sdk")
    sdk = Path(args.sdk)
    analyzer = sdk / "cmdline-tools/latest/bin/apkanalyzer"
    require(analyzer.is_file(), "Install Android SDK cmdline-tools;latest")
    results = {variant: verify(args.apk_dir / variant / f"app-{variant}.apk", radio,
                              analyzer, sdk / "build-tools" / args.build_tools)
               for variant, radio in [("sweep", False), ("sweepRadio", True)]}
    require(results["sweep"]["versionCode"] == results["sweepRadio"]["versionCode"], "Variant versions differ")
    print(json.dumps({"installers": results, "physicalPerformanceVerified": False,
                      "productionReleaseVerified": False}, indent=2))


if __name__ == "__main__":
    main()
