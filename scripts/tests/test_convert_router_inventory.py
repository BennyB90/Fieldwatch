import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import convert_router_inventory as converter  # noqa: E402


class ConvertRouterInventoryTests(unittest.TestCase):
    def setUp(self):
        self.captured_at = 1_760_000_000_000

    def test_dnsmasq_omits_future_lease_expiry_and_merges_dual_stack_mac(self):
        raw = "\n".join([
            "1760086400 aa:bb:cc:dd:ee:01 192.168.1.20 kitchen-cam 01:aa:bb:cc:dd:ee:01",
            "1760086400 AA-BB-CC-DD-EE-01 fd00::20 * *",
            "0 02:11:22:33:44:55 010.000.000.005 nas-box custom-client-5",
        ])
        doc, dropped = converter.convert_inventory(raw, fmt="auto", captured_at_ms=self.captured_at, network_label=" Home Wi-Fi ")
        self.assertEqual(0, dropped)
        self.assertEqual(1, doc["schemaVersion"])
        self.assertEqual(self.captured_at, doc["capturedAt"])
        self.assertEqual("Home Wi-Fi", doc["networkLabel"])
        self.assertEqual(2, len(doc["devices"]))
        by_mac = {item["macAddress"]: item for item in doc["devices"]}
        cam = by_mac["AA:BB:CC:DD:EE:01"]
        self.assertEqual("kitchen-cam", cam["hostname"])
        self.assertEqual(["192.168.1.20", "fd00::20"], cam["addresses"])
        self.assertNotIn("lastSeenAt", cam)
        self.assertNotIn("reportedIdentifier", cam)
        nas = by_mac["02:11:22:33:44:55"]
        self.assertEqual(["10.0.0.5"], nas["addresses"])
        self.assertEqual("custom-client-5", nas["reportedIdentifier"])

    def test_isc_dhcpd_prefers_active_lease_when_ip_is_reused(self):
        raw = """
        lease 192.168.50.10 {
          starts 4 2025/10/01 10:00:00;
          ends 4 2099/10/01 12:00:00;
          binding state free;
          hardware ethernet 02:00:00:00:00:01;
        }
        lease 192.168.50.10 {
          starts 4 2025/10/02 10:00:00;
          ends 4 2099/10/02 12:00:00;
          binding state active;
          hardware ethernet 02:00:00:00:00:02;
          client-hostname "thermostat";
        }
        """
        doc, _ = converter.convert_inventory(raw, fmt="auto", captured_at_ms=self.captured_at)
        by_mac = {item["macAddress"]: item for item in doc["devices"]}
        self.assertEqual(["192.168.50.10"], by_mac["02:00:00:00:00:02"]["addresses"])
        self.assertTrue(by_mac["02:00:00:00:00:02"]["current"])
        self.assertLessEqual(by_mac["02:00:00:00:00:02"]["lastSeenAt"], self.captured_at)
        self.assertNotIn("addresses", by_mac["02:00:00:00:00:01"])
        self.assertFalse(by_mac["02:00:00:00:00:01"]["current"])

    def test_unifi_json_strips_bom_controls_and_canonicalizes_ipv6_duplicates(self):
        unifi = {
            "meta": {"rc": "ok"},
            "data": [
                {
                    "_id": "650001",
                    "mac": "aabb.ccdd.ee10",
                    "hostname": "office\u200b-ap\n",
                    "ip": "192.168.10.5/24",
                    "ipv6": ["FD00:0:0:0:0:0:0:1", "fd00::1"],
                    "is_online": True,
                    "last_seen": 1_759_999_900,
                    "x_passphrase": "ignored-secret-field",
                },
            ],
        }
        raw = "\ufeff" + json.dumps(unifi)
        doc, _ = converter.convert_inventory(raw, fmt="auto", captured_at_ms=self.captured_at)
        self.assertEqual(set(doc.keys()), {"schemaVersion", "capturedAt", "devices"})
        dev = doc["devices"][0]
        self.assertEqual("AA:BB:CC:DD:EE:10", dev["macAddress"])
        self.assertEqual("office -ap", dev["hostname"])
        self.assertEqual(["192.168.10.5", "fd00::1"], dev["addresses"])
        self.assertEqual(1_759_999_900_000, dev["lastSeenAt"])
        self.assertTrue(dev["current"])
        self.assertNotIn("ignored-secret-field", json.dumps(doc))

    def test_ip_neigh_and_semicolon_csv_and_128_cap(self):
        neigh = "\n".join([
            "192.168.1.1 dev wlan0 lladdr 0:1a:2b:3c:4d:5e REACHABLE",
            "fe80::1%wlan0 dev wlan0 lladdr 00:1a:2b:3c:4d:5e STALE",
            "192.168.1.99 dev wlan0 FAILED",
        ])
        doc, _ = converter.convert_inventory(neigh, fmt="auto", captured_at_ms=self.captured_at)
        by_mac = {d.get("macAddress"): d for d in doc["devices"]}
        self.assertEqual(["192.168.1.1", "fe80::1%wlan0"], by_mac["00:1A:2B:3C:4D:5E"]["addresses"])

        rows = ["hostname;ip;mac;active"] + [
            f"host-{i};10.1.0.{i};02:00:00:00:{i // 256:02x}:{i % 256:02x};{'1' if i % 2 == 0 else '0'}"
            for i in range(1, 140)
        ]
        capped_doc, dropped = converter.convert_inventory("\n".join(rows), fmt="auto", captured_at_ms=self.captured_at)
        self.assertEqual(128, len(capped_doc["devices"]))
        self.assertEqual(11, dropped)

    def test_cli_writes_utf8_without_bom(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            src = tmp_path / "dhcp.leases"
            out = tmp_path / "sweep-inventory.json"
            src.write_text("1760086400 02:aa:bb:cc:dd:ee 192.168.1.50 printer *\n", encoding="utf-8-sig")
            rc = converter.main([str(src), "-o", str(out), "--captured-at", str(self.captured_at), "--label", "Lab"])
            self.assertEqual(0, rc)
            raw = out.read_bytes()
            self.assertFalse(raw.startswith(b"\xef\xbb\xbf"))
            parsed = json.loads(raw.decode("utf-8"))
            self.assertEqual("Lab", parsed["networkLabel"])
            self.assertEqual("02:AA:BB:CC:DD:EE", parsed["devices"][0]["macAddress"])


if __name__ == "__main__":
    unittest.main()
