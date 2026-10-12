"""Failure-focused protocol checks. Fixtures are synthetic test data, not RF evidence."""

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import evidence


def record_common(row):
    row.update({
        "status": "complete",
        "phone": {"id": "TEST-PHONE", "model": "UNIT-TEST-FIXTURE", "android_version": "10", "android_api": 29},
        "scanner": {"build_commit": "UNIT-TEST-FIXTURE", "settings_record": "fixture-only"},
        "started_at_utc": "2000-01-01T00:00:00Z",
        "evidence_ref": "UNIT-TEST-FIXTURE-NOT-HARDWARE-EVIDENCE",
    })


def complete_trial(row, detected=True):
    record_common(row)
    row["target_state"] = {"model": "UNIT-TEST-FIXTURE", "firmware": "unknown", "state": "fixture-only"}
    row["ground_truth"] = {"target_present": True, "physically_confirmed": True,
                           "physical_item_type": row["expected_physical_item_type"],
                           "reference_identity": "UNIT-TEST-EMITTER",
                           "source_kind": row["expected_source_kind"]}
    row["scan"] = {"window_seconds": 60, "live_since_session_start": True, "timestamps_verified": True,
                   "ble_scan_running_verified": True, "ble_scan_evidence_ref": "fixture-ble-running",
                   "wifi_scan_completed": True, "wifi_scan_evidence_ref": "fixture-wifi-scan-completed",
                   "samples_received": 1 if detected else 0, "max_sample_age_seconds": 1 if detected else None}
    row["observation"] = {"signal_available": detected, "detected": detected,
                          "classification": row["expected_class"] if detected else None,
                          "matches_reference_identity": detected,
                          "latency_seconds": 20 if detected else None, "rssi_dbm": None}
    if row["expected_class"] == "remote_id":
        row["remote_id"] = {"protocol": "OpenDroneID-fixture" if detected else None,
                            "protocol_valid": detected, "raw_capture_ref": "fixture-only" if detected else None,
                            "locations": {"aircraft": {"latitude": 20.5, "longitude": 10.0,
                                                       "message_location_type": "aircraft"} if detected else None,
                                          "operator": None, "takeoff": None}}
    return row


def complete_baseline(row, alarms=0):
    record_common(row)
    row.update({"window_seconds": 60, "target_inventory_ref": "fixture-inventory", "camera_target_absent_confirmed": True,
                "live_since_session_start": True, "timestamps_verified": True,
                "wifi_scan_completed": True, "wifi_scan_evidence_ref": "fixture-wifi-scan-completed",
                "samples_received": 1, "max_sample_age_seconds": 1, "camera_alarm_count": alarms})


def complete_battery(row, drain=9):
    record_common(row)
    row.update({"ended_at_utc": "2000-01-01T00:20:00Z", "session_duration_seconds": 1200,
                "start_battery_percent": 100, "end_battery_percent": 100 - drain,
                "charging_during_session": False, "screen_and_radio_settings": "fixture-only"})


def tracker_cell(document, target="airtag", environment="office"):
    return [row for row in document["trials"] if row["target"] == target and
            row["distance_m"] == 10 and row["environment"] == environment]


def tracker_result(document, target="airtag", environment="office"):
    return next(result for result in evidence.summarize(document)["tracker_10m_gates"]
                if result["target"] == target and result["environment"] == environment)


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.document = evidence.template()

    def test_template_has_240_pending_trials_and_no_measurements(self):
        self.assertEqual(len(self.document["trials"]), 240)
        for row in self.document["trials"]:
            self.assertEqual(row["status"], "pending")
            self.assertTrue(all(value is None for value in row["scan"].values()))
            self.assertTrue(all(value is None for value in row["observation"].values()))
        result = evidence.summarize(self.document)
        self.assertTrue(result["document_valid"])
        self.assertEqual(result["trial_coverage"]["pending"], 240)
        self.assertTrue(all(item["status"] == "incomplete" for item in result["tracker_10m_gates"]))
        self.assertEqual(result["camera_baseline"]["status"], "incomplete")
        self.assertTrue(result["camera_baseline"]["gate_declared"])

    def test_no_detection_counts_in_predeclared_denominator(self):
        for index, row in enumerate(tracker_cell(self.document)):
            complete_trial(row, detected=index < 8)
        result = tracker_result(self.document)
        self.assertEqual(result["coverage"]["valid"], 10)
        self.assertEqual(result["fraction_of_planned_trials"], 0.8)
        self.assertEqual(result["status"], "fail")

    def test_missing_trial_cannot_pass_even_with_nine_hits(self):
        cell = tracker_cell(self.document)
        for row in cell[:9]:
            complete_trial(row)
        self.document["trials"].remove(cell[9])
        result = tracker_result(self.document)
        self.assertEqual(result["target_hits_within_60s"], 9)
        self.assertEqual(result["planned_denominator"], 10)
        self.assertEqual(result["coverage"]["missing"], 1)
        self.assertEqual(result["status"], "incomplete")

    def test_airtag_success_does_not_pass_tile_or_other_environment(self):
        for row in tracker_cell(self.document):
            complete_trial(row)
        self.assertEqual(tracker_result(self.document)["status"], "pass")
        self.assertEqual(tracker_result(self.document, "tile_mate")["status"], "incomplete")
        self.assertEqual(tracker_result(self.document, environment="quiet")["status"], "incomplete")

    def test_stale_cache_is_invalid_and_not_a_target_hit(self):
        for row in tracker_cell(self.document):
            complete_trial(row)
        tracker_cell(self.document)[0]["scan"]["max_sample_age_seconds"] = 61
        result = tracker_result(self.document)
        self.assertEqual(result["coverage"]["invalid"], 1)
        self.assertEqual(result["target_hits_within_60s"], 9)
        self.assertEqual(result["status"], "incomplete")

    def test_wrong_observed_class_is_a_valid_failure(self):
        for index, row in enumerate(tracker_cell(self.document)):
            complete_trial(row)
            if index < 2:
                row["observation"]["classification"] = "unknown_ble"
        result = tracker_result(self.document)
        self.assertEqual(result["coverage"]["valid"], 10)
        self.assertEqual(result["status"], "fail")

    def test_tracker_cell_cannot_pool_phone_model_os_or_build_contexts(self):
        for field in ("id", "model", "android_version", "android_api"):
            with self.subTest(field=field):
                document = evidence.template()
                for row in tracker_cell(document):
                    complete_trial(row)
                row = tracker_cell(document)[0]
                row["phone"][field] = 30 if field == "android_api" else "SECOND-TEST-CONTEXT"
                result = tracker_result(document)
                self.assertEqual(result["target_hits_within_60s"], 10)
                self.assertEqual(len(result["phone_contexts"]), 2)
                self.assertEqual(result["status"], "invalid_cell")
        document = evidence.template()
        for row in tracker_cell(document):
            complete_trial(row)
        tracker_cell(document)[0]["scanner"]["build_commit"] = "SECOND-TEST-BUILD"
        self.assertEqual(tracker_result(document)["status"], "invalid_cell")

    def test_tracker_miss_requires_verified_running_ble_scan(self):
        row = complete_trial(tracker_cell(self.document)[0], detected=False)
        row["scan"]["ble_scan_running_verified"] = False
        result = tracker_result(self.document)
        self.assertEqual(result["coverage"]["invalid"], 1)
        self.assertEqual(result["planned_denominator"], 10)

    def test_synthetic_remote_id_cannot_establish_real_aircraft_validation(self):
        for row in self.document["trials"]:
            if row["expected_class"] == "remote_id":
                complete_trial(row)
        result = evidence.summarize(self.document)["remote_id"]
        self.assertEqual(result["synthetic_ble_decoder"]["status"], "pass")
        self.assertEqual(result["real_aircraft_validation"]["status"], "not_evaluated")
        row = next(row for row in self.document["trials"] if row["expected_class"] == "remote_id")
        row["ground_truth"]["source_kind"] = "real_device"
        result = evidence.summarize(self.document)["remote_id"]
        self.assertEqual(result["synthetic_ble_decoder"]["coverage"]["invalid"], 1)
        self.assertEqual(result["synthetic_ble_decoder"]["status"], "incomplete")

    def test_takeoff_location_cannot_be_relabelled_operator(self):
        row = next(row for row in self.document["trials"] if row["expected_class"] == "remote_id")
        complete_trial(row)
        row["remote_id"]["locations"]["operator"] = {"latitude": 20.5, "longitude": 10.0, "message_location_type": "takeoff"}
        result = evidence.summarize(self.document)
        self.assertEqual(result["remote_id"]["synthetic_ble_decoder"]["coverage"]["invalid"], 1)
        self.assertTrue(any("wrong location semantics" in error for issue in result["measurement_issues"] for error in issue["errors"]))

    def test_zero_zero_sentinel_cannot_pass_the_aircraft_decoder_gate(self):
        for row in self.document["trials"]:
            if row["expected_class"] == "remote_id":
                complete_trial(row)
                row["remote_id"]["locations"]["aircraft"].update({"latitude": 0, "longitude": 0})
        result = evidence.summarize(self.document)["remote_id"]["synthetic_ble_decoder"]
        self.assertEqual(result["coverage"]["invalid"], 60)
        self.assertEqual(result["protocol_qualified_map_pins"]["aircraft"], 0)
        self.assertEqual(result["status"], "incomplete")

    def test_unqualified_remote_id_cannot_supply_trusted_pin(self):
        row = next(row for row in self.document["trials"] if row["expected_class"] == "remote_id")
        complete_trial(row)
        row["remote_id"]["protocol_valid"] = False
        result = evidence.summarize(self.document)
        self.assertEqual(result["remote_id"]["synthetic_ble_decoder"]["protocol_qualified_map_pins"]["aircraft"], 0)
        self.assertEqual(result["trial_coverage"]["invalid"], 1)

    def test_baseline_cannot_pass_without_a_predeclared_gate(self):
        self.document["protocol"]["camera_baseline_gate"].update({
            "max_session_false_alarm_fraction": None, "minimum_valid_sessions": None,
            "declared_before_first_trial": None, "declaration_evidence_ref": None})
        for row in self.document["baseline_controls"]:
            complete_baseline(row)
        result = evidence.summarize(self.document)["camera_baseline"]
        self.assertEqual(result["valid_denominator"], 10)
        self.assertEqual(result["false_alarm_fraction"], 0)
        self.assertEqual(result["status"], "gate_undeclared")

    def test_declared_zero_alarm_screening_rejects_one_alerting_session(self):
        for index, row in enumerate(self.document["baseline_controls"]):
            complete_baseline(row, alarms=1 if index == 0 else 0)
        result = evidence.summarize(self.document)["camera_baseline"]
        self.assertEqual(result["valid_denominator"], 10)
        self.assertEqual(result["false_alarm_fraction"], 0.1)
        self.assertEqual(result["status"], "fail")

    def test_baseline_false_alarm_rate_uses_sessions_not_rf_packets(self):
        self.document["protocol"]["camera_baseline_gate"].update({
            "max_session_false_alarm_fraction": 0.1, "minimum_valid_sessions": 10,
            "declared_before_first_trial": True, "declaration_evidence_ref": "fixture-policy"})
        for index, row in enumerate(self.document["baseline_controls"]):
            complete_baseline(row, alarms=500 if index < 2 else 0)
        result = evidence.summarize(self.document)["camera_baseline"]
        self.assertEqual(result["false_alarm_fraction"], 0.2)
        self.assertEqual(result["status"], "fail")

    def test_empty_or_disabled_wifi_baseline_cannot_pass(self):
        self.document["protocol"]["camera_baseline_gate"].update({
            "max_session_false_alarm_fraction": 0.1, "minimum_valid_sessions": 10,
            "declared_before_first_trial": True, "declaration_evidence_ref": "fixture-policy"})
        for row in self.document["baseline_controls"]:
            complete_baseline(row)
        self.assertEqual(evidence.summarize(self.document)["camera_baseline"]["status"], "pass")
        row = self.document["baseline_controls"][0]
        row.update({"samples_received": 0, "max_sample_age_seconds": None})
        self.assertEqual(evidence.summarize(self.document)["camera_baseline"]["status"], "incomplete")
        complete_baseline(row)
        row["wifi_scan_completed"] = False
        self.assertEqual(evidence.summarize(self.document)["camera_baseline"]["coverage"]["invalid"], 1)
        complete_baseline(row)
        row["wifi_scan_evidence_ref"] = None
        self.assertEqual(evidence.summarize(self.document)["camera_baseline"]["status"], "incomplete")

    def test_camera_positive_requires_independent_wifi_scan_confirmation(self):
        row = next(row for row in self.document["trials"] if row["target"] == "wifi_camera_ap")
        complete_trial(row)
        row["scan"]["wifi_scan_completed"] = False
        result = evidence.summarize(self.document)
        self.assertEqual(result["camera_positive_trials"]["matching_indicator_hits"], 0)
        self.assertEqual(result["camera_positive_trials"]["coverage"]["invalid"], 1)

    def test_battery_requires_less_than_ten_points_and_exact_twenty_minutes(self):
        row = self.document["battery_controls"][0]
        complete_battery(row, drain=10)
        self.assertEqual(evidence.summarize(self.document)["battery_sessions"][0]["status"], "fail")
        complete_battery(row, drain=9)
        self.assertEqual(evidence.summarize(self.document)["battery_sessions"][0]["status"], "pass")
        row["ended_at_utc"] = "2000-01-01T00:19:59Z"
        self.assertEqual(evidence.summarize(self.document)["battery_sessions"][0]["validity"], "invalid")

    def test_battery_pass_on_one_phone_cannot_cover_another(self):
        complete_battery(self.document["battery_controls"][0])
        row = complete_trial(self.document["trials"][0])
        row["phone"]["id"] = "SECOND-TEST-PHONE"
        phones = evidence.summarize(self.document)["battery_per_phone"]
        self.assertEqual(next(item for item in phones if item["phone_id"] == "SECOND-TEST-PHONE")["status"], "incomplete")

    def test_duplicate_record_cannot_increase_coverage_or_pass(self):
        for row in tracker_cell(self.document):
            complete_trial(row)
        self.document["trials"].append(copy.deepcopy(tracker_cell(self.document)[0]))
        result = evidence.summarize(self.document)
        self.assertFalse(result["document_valid"])
        self.assertEqual(result["trial_coverage"]["planned"], 240)
        self.assertEqual(tracker_result(self.document)["status"], "incomplete")


if __name__ == "__main__":
    unittest.main()
