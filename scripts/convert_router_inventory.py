#!/usr/bin/env python3
"""Convert common authorized router client/DHCP/ARP exports into SWEEP schemaVersion 1 JSON.

Performs local offline file conversion only: never connects to a router, accepts credentials,
or performs DNS lookups. Output conforms strictly to SWEEP's RouterInventoryParser rules
(UTF-8 without BOM, <= 1 MiB, <= 128 devices, canonical unique MACs/IPs/identifiers,
past-only epoch-millisecond timestamps).
"""
from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
import io
import ipaddress
import json
from pathlib import Path
import re
import sys
import time
import unicodedata
from typing import Any

MAX_FILE_BYTES = 1_048_576
MAX_DEVICES = 128
MAX_ADDRESSES = 16
MAX_LABEL_LEN = 120
MAX_HOSTNAME_LEN = 255
MAX_IDENTIFIER_LEN = 128
SCOPE_RE = re.compile(r"^[A-Za-z0-9_.-]{1,32}$")
MAC_COLON_HYPHEN_RE = re.compile(r"^(?:[0-9A-Fa-f]{1,2}[:-]){5}[0-9A-Fa-f]{1,2}$")
MAC_CISCO_RE = re.compile(r"^[0-9A-Fa-f]{4}\.[0-9A-Fa-f]{4}\.[0-9A-Fa-f]{4}$")
MAC_BARE_RE = re.compile(r"^[0-9A-Fa-f]{12}$")
PLACEHOLDERS = {"", "*", "?", "-", "--", "unknown", "<unknown>", "(unknown)", "n/a", "none", "null", "<incomplete>", "incomplete"}

MAC_KEYS = ("macaddress", "mac", "mac_address", "hwaddr", "hardware_address", "bssid", "ether", "lladdr", "client_mac", "phys_address")
IP_KEYS = ("addresses", "ip", "ip_address", "ipaddress", "ipv4", "ipv6", "address", "addr", "fixed_ip", "client_ip", "leased_address")
HOST_KEYS = ("hostname", "host", "host_name", "name", "client_hostname", "device_name", "devicename", "alias", "description")
ID_KEYS = ("reportedidentifier", "reported_identifier", "client_id", "clientid", "duid", "id", "_id", "uid")
CURRENT_KEYS = ("current", "active", "online", "is_online", "connected", "reachable", "in_use")
LAST_SEEN_KEYS = ("lastseenat", "last_seen_at", "last_seen", "lastseen", "seen_at", "updated_at")


def clean_text(raw: Any, max_chars: int) -> str | None:
    if raw is None or isinstance(raw, (bool, dict, list)):
        return None
    filtered = "".join(
        " " if (unicodedata.category(ch) in ("Cc", "Cf") or ord(ch) < 32 or 127 <= ord(ch) <= 159) else ch
        for ch in str(raw)
    )
    compact = re.sub(r"\s+", " ", filtered).strip()
    if not compact or compact.lower() in PLACEHOLDERS:
        return None
    # Match Kotlin UTF-16 code-unit length bounds even for astral characters.
    encoded = compact.encode("utf-16-le")
    if len(encoded) > max_chars * 2:
        compact = encoded[: max_chars * 2].decode("utf-16-le", errors="ignore").strip()
    return compact or None


def normalize_mac(raw: Any) -> str | None:
    text = clean_text(raw, 32)
    if not text:
        return None
    if MAC_COLON_HYPHEN_RE.match(text):
        parts = re.split(r"[:-]", text)
        octets = [int(part, 16) for part in parts]
    elif MAC_CISCO_RE.match(text):
        hex_digits = text.replace(".", "")
        octets = [int(hex_digits[i : i + 2], 16) for i in range(0, 12, 2)]
    elif MAC_BARE_RE.match(text):
        octets = [int(text[i : i + 2], 16) for i in range(0, 12, 2)]
    else:
        return None
    formatted = ":".join(f"{b:02X}" for b in octets)
    if formatted in ("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF"):
        return None
    return formatted


def canonical_address(raw: Any) -> tuple[str, str] | None:
    """Return (formatted_literal, canonical_uniqueness_key) matching NetworkAddressValidator.kt."""
    text = clean_text(raw, 96)
    if not text:
        return None
    if text.startswith("[") and text.endswith("]"):
        text = text[1:-1]
    # Strip CIDR prefix length when router tables report e.g. 192.168.1.20/24 or fd00::20/64.
    if "/" in text:
        text = text.split("/", 1)[0].strip()
    scope = None
    addr_part = text
    if "%" in text:
        addr_part, scope = text.split("%", 1)
        if not SCOPE_RE.match(scope):
            return None
    if "." in addr_part and ":" not in addr_part:
        octets = addr_part.split(".")
        if len(octets) != 4 or any(not part.isdigit() for part in octets):
            return None
        nums = [int(part) for part in octets]
        if any(n < 0 or n > 255 for n in nums):
            return None
        formatted_v4 = ".".join(str(n) for n in nums)
        ip_obj = ipaddress.IPv4Address(formatted_v4)
        if ip_obj.is_unspecified or ip_obj.is_multicast or formatted_v4 == "255.255.255.255":
            return None
        if scope is not None:
            return None
        return formatted_v4, f"v4:{formatted_v4}"
    try:
        ip6 = ipaddress.IPv6Address(addr_part)
    except ValueError:
        return None
    if ip6.is_unspecified or ip6.is_multicast:
        return None
    hextets = [f"{(int(ip6) >> (16 * (7 - i))) & 0xFFFF:x}" for i in range(8)]
    expanded = ":".join(hextets)
    formatted_v6 = ip6.compressed + (f"%{scope}" if scope else "")
    if len(formatted_v6) < 2 or len(formatted_v6) > 80:
        return None
    key = f"v6:{expanded}" + (f"%{scope}" if scope else "")
    return formatted_v6, key


def parse_past_timestamp_ms(raw: Any, captured_at_ms: int) -> int | None:
    if raw is None or isinstance(raw, bool):
        return None
    if isinstance(raw, (int, float)):
        value = int(raw)
        if value <= 0:
            return None
        # Distinguish epoch seconds vs milliseconds (~1e11 ms is year 1973; 1e11 s is year 5138).
        ms = value * 1000 if value < 100_000_000_000 else value
        return ms if 1 <= ms <= captured_at_ms else None
    text = str(raw).strip()
    if not text or text.lower() in PLACEHOLDERS:
        return None
    if text.isdigit():
        return parse_past_timestamp_ms(int(text), captured_at_ms)
    normalized = text[:-1] + "+00:00" if text.endswith(("Z", "z")) else text
    for fmt in (None, "%Y/%m/%d %H:%M:%S", "%Y-%m-%d %H:%M:%S"):
        try:
            if fmt is None:
                dt = datetime.fromisoformat(normalized)
            else:
                dt = datetime.strptime(text, fmt).replace(tzinfo=timezone.utc)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            ms = int(dt.timestamp() * 1000)
            if 1 <= ms <= captured_at_ms:
                return ms
        except ValueError:
            continue
    return None


def parse_current_flag(raw: Any) -> bool | None:
    if isinstance(raw, bool):
        return raw
    if isinstance(raw, int):
        return True if raw == 1 else (False if raw == 0 else None)
    if isinstance(raw, str):
        token = raw.strip().lower()
        if token in ("true", "yes", "online", "active", "connected", "reachable", "delay", "probe", "permanent", "up", "1"):
            return True
        if token in ("false", "no", "offline", "inactive", "disconnected", "stale", "failed", "expired", "free", "down", "0"):
            return False
    return None


def extract_field(mapping: dict[str, Any], candidates: tuple[str, ...]) -> Any:
    lowered = {re.sub(r"[\s-]+", "_", str(k).strip().lower()): v for k, v in mapping.items()}
    for key in candidates:
        if key in lowered and lowered[key] is not None:
            return lowered[key]
    return None


def record_from_mapping(row: dict[str, Any], captured_at_ms: int) -> dict[str, Any] | None:
    mac = normalize_mac(extract_field(row, MAC_KEYS))
    hostname = clean_text(extract_field(row, HOST_KEYS), MAX_HOSTNAME_LEN)
    if hostname and normalize_mac(hostname) == mac:
        hostname = None
    identifier = clean_text(extract_field(row, ID_KEYS), MAX_IDENTIFIER_LEN)
    if identifier and (identifier == mac or identifier == hostname):
        identifier = None
    raw_ips = extract_field(row, IP_KEYS)
    ip_candidates: list[Any] = []
    if isinstance(raw_ips, list):
        ip_candidates.extend(raw_ips)
    elif isinstance(raw_ips, str):
        ip_candidates.extend(re.split(r"[,;\s]+", raw_ips.strip()))
    elif raw_ips is not None:
        ip_candidates.append(raw_ips)
    # Also scan secondary IPv4/IPv6 columns if both exist in a CSV/JSON row.
    for extra_key in ("ipv4", "ipv6", "fixed_ip", "local_ip"):
        val = extract_field(row, (extra_key,))
        if isinstance(val, list):
            ip_candidates.extend(val)
        elif val is not None:
            ip_candidates.append(val)
    addresses: list[tuple[str, str]] = []
    seen_addr_keys: set[str] = set()
    for candidate in ip_candidates:
        parsed = canonical_address(candidate)
        if parsed and parsed[1] not in seen_addr_keys:
            seen_addr_keys.add(parsed[1])
            addresses.append(parsed)
    current = parse_current_flag(extract_field(row, CURRENT_KEYS))
    # Never map DHCP 'expires' / 'ends' columns to lastSeenAt!
    last_seen = parse_past_timestamp_ms(extract_field(row, LAST_SEEN_KEYS), captured_at_ms)
    if not (hostname or addresses or mac or identifier):
        return None
    return {
        "hostname": hostname,
        "addresses": addresses,
        "macAddress": mac,
        "reportedIdentifier": identifier,
        "current": current,
        "lastSeenAt": last_seen,
    }


def parse_dnsmasq(text: str) -> list[dict[str, Any]]:
    records = []
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) < 3 or not (parts[0].isdigit() or parts[0] == "0"):
            continue
        mac = normalize_mac(parts[1])
        addr = canonical_address(parts[2])
        hostname = clean_text(parts[3], MAX_HOSTNAME_LEN) if len(parts) >= 4 else None
        client_id = clean_text(parts[4], MAX_IDENTIFIER_LEN) if len(parts) >= 5 else None
        if client_id and normalize_mac(client_id.removeprefix("01:")) == mac:
            client_id = None
        if not (mac or addr or hostname or client_id):
            continue
        # Column 0 in dnsmasq is future lease expiry (or 0 for static), NEVER lastSeenAt.
        records.append({
            "hostname": hostname,
            "addresses": [addr] if addr else [],
            "macAddress": mac,
            "reportedIdentifier": client_id,
            "current": None,
            "lastSeenAt": None,
        })
    return records


def parse_isc_dhcpd(text: str, captured_at_ms: int) -> list[dict[str, Any]]:
    records = []
    for match in re.finditer(r"lease\s+([^\s{]+)\s*\{([^}]*)\}", text, re.MULTILINE | re.DOTALL):
        addr = canonical_address(match.group(1))
        body = match.group(2)
        mac_match = re.search(r"hardware\s+ethernet\s+([^;\s]+)\s*;", body, re.IGNORECASE)
        host_match = re.search(r'client-hostname\s+"([^"]*)"\s*;', body, re.IGNORECASE)
        state_match = re.search(r"binding\s+state\s+([A-Za-z0-9_-]+)\s*;", body, re.IGNORECASE)
        starts_match = re.search(r"starts\s+\d+\s+(\d{4}/\d{2}/\d{2}\s+\d{2}:\d{2}:\d{2})\s*;", body, re.IGNORECASE)
        uid_match = re.search(r'uid\s+"([^"]*)"\s*;', body, re.IGNORECASE)
        mac = normalize_mac(mac_match.group(1)) if mac_match else None
        hostname = clean_text(host_match.group(1), MAX_HOSTNAME_LEN) if host_match else None
        current = parse_current_flag(state_match.group(1)) if state_match else None
        last_seen = parse_past_timestamp_ms(starts_match.group(1), captured_at_ms) if starts_match else None
        uid = clean_text(uid_match.group(1), MAX_IDENTIFIER_LEN) if uid_match else None
        if not (addr or mac or hostname or uid):
            continue
        records.append({
            "hostname": hostname,
            "addresses": [addr] if addr else [],
            "macAddress": mac,
            "reportedIdentifier": uid,
            "current": current,
            "lastSeenAt": last_seen,
        })
    return records


def parse_ip_neigh_or_arp(text: str) -> list[dict[str, Any]]:
    records = []
    bsd_arp = re.compile(r"^(?P<host>\S+)\s+\((?P<ip>[^)]+)\)\s+at\s+(?P<mac>\S+)")
    win_arp = re.compile(r"^\s*(?P<ip>\d{1,3}(?:\.\d{1,3}){3})\s+(?P<mac>(?:[0-9A-Fa-f]{2}-){5}[0-9A-Fa-f]{2})\s+(?P<type>\S+)")
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", "Interface:", "Internet Address", "Address")):
            continue
        m_bsd = bsd_arp.match(line)
        if m_bsd:
            addr = canonical_address(m_bsd.group("ip"))
            mac = normalize_mac(m_bsd.group("mac"))
            host = clean_text(m_bsd.group("host"), MAX_HOSTNAME_LEN)
            if addr or mac or host:
                records.append({"hostname": host, "addresses": [addr] if addr else [], "macAddress": mac,
                                "reportedIdentifier": None, "current": True if mac else None, "lastSeenAt": None})
            continue
        m_win = win_arp.match(line)
        if m_win:
            addr = canonical_address(m_win.group("ip"))
            mac = normalize_mac(m_win.group("mac"))
            if addr or mac:
                records.append({"hostname": None, "addresses": [addr] if addr else [], "macAddress": mac,
                                "reportedIdentifier": None, "current": None, "lastSeenAt": None})
            continue
        # Linux `ip neigh`: <ip> dev <iface> lladdr <mac> <STATE>
        tokens = line.split()
        if len(tokens) >= 4 and "dev" in tokens:
            addr = canonical_address(tokens[0])
            mac = None
            if "lladdr" in tokens:
                idx = tokens.index("lladdr")
                if idx + 1 < len(tokens):
                    mac = normalize_mac(tokens[idx + 1])
            current = parse_current_flag(tokens[-1])
            if addr or mac:
                records.append({"hostname": None, "addresses": [addr] if addr else [], "macAddress": mac,
                                "reportedIdentifier": None, "current": current, "lastSeenAt": None})
    return records


def parse_json_input(text: str, captured_at_ms: int) -> tuple[list[dict[str, Any]], str | None, int | None]:
    loaded = json.loads(text)
    inferred_label = None
    inferred_capture = None
    rows: list[Any]
    if isinstance(loaded, dict):
        if loaded.get("schemaVersion") == 1 and isinstance(loaded.get("devices"), list):
            inferred_label = clean_text(loaded.get("networkLabel"), MAX_LABEL_LEN)
            inferred_capture = parse_past_timestamp_ms(loaded.get("capturedAt"), captured_at_ms)
            rows = loaded["devices"]
        else:
            inferred_label = clean_text(loaded.get("networkLabel") or loaded.get("site_name") or loaded.get("network"), MAX_LABEL_LEN)
            for container_key in ("devices", "data", "clients", "leases", "rows", "entries", "hosts", "result"):
                candidate = loaded.get(container_key)
                if isinstance(candidate, list):
                    rows = candidate
                    break
                if isinstance(candidate, dict) and all(isinstance(v, dict) for v in candidate.values()):
                    rows = list(candidate.values())
                    break
            else:
                if all(isinstance(v, dict) for v in loaded.values()):
                    rows = list(loaded.values())
                else:
                    rows = [loaded]
    elif isinstance(loaded, list):
        rows = loaded
    else:
        raise ValueError("Unsupported JSON root type; expected an object or array of client records.")
    parsed = [rec for item in rows if isinstance(item, dict) and (rec := record_from_mapping(item, captured_at_ms)) is not None]
    return parsed, inferred_label, inferred_capture


def parse_delimited(text: str, captured_at_ms: int) -> list[dict[str, Any]]:
    non_comment = "\n".join(line for line in text.splitlines() if line.strip() and not line.lstrip().startswith("#"))
    if not non_comment:
        return []
    sample = non_comment[:4096]
    delimiter = ";" if sample.count(";") > sample.count(",") and sample.count(";") >= sample.count("\t") else (
        "\t" if sample.count("\t") > sample.count(",") else ","
    )
    reader = csv.DictReader(io.StringIO(non_comment), delimiter=delimiter)
    if not reader.fieldnames:
        return []
    return [rec for row in reader if (rec := record_from_mapping(row, captured_at_ms)) is not None]


def consolidate_records(raw_records: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], int]:
    """Merge multi-address records by MAC, deduplicate IPs/identifiers globally, and cap at 128."""
    # Sort active and newer records first so active leases win IP ownership over stale ones.
    ordered = sorted(
        enumerate(raw_records),
        key=lambda item: (
            1 if item[1].get("current") is True else (0 if item[1].get("current") is None else -1),
            item[1].get("lastSeenAt") or 0,
            -item[0],
        ),
        reverse=True,
    )
    by_mac: dict[str, dict[str, Any]] = {}
    unkeyed: list[dict[str, Any]] = []
    for _, rec in ordered:
        mac = rec.get("macAddress")
        if mac:
            existing = by_mac.get(mac)
            if existing is None:
                by_mac[mac] = {
                    "hostname": rec["hostname"],
                    "addresses": list(rec["addresses"]),
                    "macAddress": mac,
                    "reportedIdentifier": rec["reportedIdentifier"],
                    "current": rec["current"],
                    "lastSeenAt": rec["lastSeenAt"],
                }
            else:
                if not existing["hostname"] and rec["hostname"]:
                    existing["hostname"] = rec["hostname"]
                existing_keys = {k for _, k in existing["addresses"]}
                for addr_pair in rec["addresses"]:
                    if addr_pair[1] not in existing_keys:
                        existing_keys.add(addr_pair[1])
                        existing["addresses"].append(addr_pair)
                if not existing["reportedIdentifier"] and rec["reportedIdentifier"]:
                    existing["reportedIdentifier"] = rec["reportedIdentifier"]
                if rec["current"] is True or (existing["current"] is None and rec["current"] is not None):
                    existing["current"] = rec["current"]
                if rec["lastSeenAt"] is not None and (existing["lastSeenAt"] is None or rec["lastSeenAt"] > existing["lastSeenAt"]):
                    existing["lastSeenAt"] = rec["lastSeenAt"]
        else:
            unkeyed.append({
                "hostname": rec["hostname"],
                "addresses": list(rec["addresses"]),
                "macAddress": None,
                "reportedIdentifier": rec["reportedIdentifier"],
                "current": rec["current"],
                "lastSeenAt": rec["lastSeenAt"],
            })

    combined = list(by_mac.values()) + unkeyed
    combined.sort(
        key=lambda r: (
            1 if r["current"] is True else (0 if r["current"] is None else -1),
            r["lastSeenAt"] or 0,
            r["macAddress"] or "",
            r["hostname"] or "",
        ),
        reverse=True,
    )

    claimed_ips: set[str] = set()
    claimed_ids: set[str] = set()
    seen_tuples: set[tuple[Any, ...]] = set()
    final_devices: list[dict[str, Any]] = []

    for rec in combined:
        unique_addrs: list[str] = []
        for literal, key in rec["addresses"]:
            if key not in claimed_ips and len(unique_addrs) < MAX_ADDRESSES:
                claimed_ips.add(key)
                unique_addrs.append(literal)
        identifier = rec["reportedIdentifier"]
        if identifier and identifier in claimed_ids:
            identifier = None
        if identifier:
            claimed_ids.add(identifier)
        hostname = rec["hostname"]
        mac = rec["macAddress"]
        if not (hostname or unique_addrs or mac or identifier):
            continue
        signature = (
            hostname or "",
            tuple(sorted(unique_addrs)),
            mac or "",
            identifier or "",
            rec["current"],
            rec["lastSeenAt"],
        )
        if signature in seen_tuples:
            continue
        seen_tuples.add(signature)
        entry: dict[str, Any] = {}
        if hostname is not None:
            entry["hostname"] = hostname
        if unique_addrs:
            entry["addresses"] = unique_addrs
        if mac is not None:
            entry["macAddress"] = mac
        if identifier is not None:
            entry["reportedIdentifier"] = identifier
        if rec["current"] is not None:
            entry["current"] = rec["current"]
        if rec["lastSeenAt"] is not None:
            entry["lastSeenAt"] = rec["lastSeenAt"]
        final_devices.append(entry)

    dropped = max(0, len(final_devices) - MAX_DEVICES)
    return final_devices[:MAX_DEVICES], dropped


def convert_inventory(
    raw_text: str,
    fmt: str = "auto",
    captured_at_ms: int | None = None,
    network_label: str | None = None,
) -> tuple[dict[str, Any], int]:
    now_ms = captured_at_ms if captured_at_ms is not None else int(time.time() * 1000)
    if now_ms <= 0:
        raise ValueError("capturedAt must be a positive Unix epoch-millisecond integer.")
    stripped = raw_text.lstrip("\ufeff").strip()
    inferred_label = None
    inferred_capture = None

    if not stripped:
        records: list[dict[str, Any]] = []
    elif fmt == "json" or (fmt == "auto" and stripped[0] in "{["):
        records, inferred_label, inferred_capture = parse_json_input(stripped, now_ms)
    elif fmt == "isc-dhcpd" or (fmt == "auto" and re.search(r"\blease\s+\d+\.\d+\.\d+\.\d+\s*\{", stripped)):
        records = parse_isc_dhcpd(stripped, now_ms)
    elif fmt == "dnsmasq" or (
        fmt == "auto"
        and all(
            line.startswith("#") or (len(line.split()) >= 3 and (line.split()[0].isdigit()) and normalize_mac(line.split()[1]) is not None)
            for line in stripped.splitlines()
            if line.strip()
        )
    ):
        records = parse_dnsmasq(stripped)
    elif fmt in ("arp", "ip-neigh") or (
        fmt == "auto"
        and any(
            (" lladdr " in line and " dev " in line)
            or re.match(r"^\S+\s+\([^)]+\)\s+at\s+\S+", line.strip())
            or re.match(r"^\s*\d{1,3}(?:\.\d{1,3}){3}\s+(?:[0-9A-Fa-f]{2}-){5}[0-9A-Fa-f]{2}\s+\S+", line)
            for line in stripped.splitlines()
        )
    ):
        records = parse_ip_neigh_or_arp(stripped)
    elif fmt in ("csv", "unifi", "asuswrt", "fritzbox", "opnsense", "pfsense") or fmt == "auto":
        records = parse_delimited(stripped, now_ms)
    else:
        raise ValueError(f"Unsupported input format: {fmt}")

    effective_capture = inferred_capture if (captured_at_ms is None and inferred_capture is not None) else now_ms
    for rec in records:
        if rec.get("lastSeenAt") is not None and rec["lastSeenAt"] > effective_capture:
            rec["lastSeenAt"] = effective_capture

    devices, dropped = consolidate_records(records)
    document: dict[str, Any] = {
        "schemaVersion": 1,
        "capturedAt": effective_capture,
    }
    final_label = clean_text(network_label, MAX_LABEL_LEN) if network_label is not None else inferred_label
    if final_label is not None:
        document["networkLabel"] = final_label
    document["devices"] = devices
    return document, dropped


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Path to authorized router export file (JSON, CSV/TSV, dnsmasq, dhcpd.leases, arp, ip neigh)")
    parser.add_argument("-o", "--output", type=Path, default=None, help="Write SWEEP schemaVersion 1 JSON to this path (default: stdout)")
    parser.add_argument("--format", choices=("auto", "json", "csv", "dnsmasq", "isc-dhcpd", "arp", "ip-neigh", "unifi", "asuswrt", "fritzbox", "opnsense", "pfsense"), default="auto")
    parser.add_argument("--captured-at", type=int, default=None, help="Export capture timestamp in Unix epoch milliseconds (default: file mtime or current time)")
    parser.add_argument("--label", default=None, help="Optional network label (up to 120 characters)")
    args = parser.parse_args(argv)

    raw_bytes = args.input.read_bytes()
    raw_text = raw_bytes.decode("utf-8-sig", errors="replace")
    default_capture = args.captured_at
    if default_capture is None:
        try:
            mtime_ms = int(args.input.stat().st_mtime * 1000)
            now_ms = int(time.time() * 1000)
            default_capture = min(mtime_ms, now_ms) if mtime_ms > 0 else now_ms
        except OSError:
            default_capture = int(time.time() * 1000)

    document, dropped = convert_inventory(raw_text, fmt=args.format, captured_at_ms=default_capture, network_label=args.label)
    rendered = json.dumps(document, indent=2, ensure_ascii=False) + "\n"
    encoded = rendered.encode("utf-8")
    if len(encoded) > MAX_FILE_BYTES:
        raise ValueError("Converted JSON exceeds SWEEP's 1 MiB limit; filter input records first.")

    if dropped > 0:
        print(f"Note: truncated {dropped} excess device record(s) to fit SWEEP's 128-device snapshot limit.", file=sys.stderr)

    if args.output is not None:
        args.output.write_bytes(encoded)
    else:
        sys.stdout.buffer.write(encoded)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
