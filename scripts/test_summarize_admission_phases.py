"""Offline validation for the direct-store phase report; no DB required."""
import json
import tempfile
import unittest
from pathlib import Path

from scripts.summarize_admission_phases import CREATE, REPLAY, markdown, summarize


def stage(count):
    return {"count": count, "p50_ms": 1.0, "p95_ms": 2.0, "sum_ms": count * 1.5}


class AdmissionPhaseSummaryTest(unittest.TestCase):
    def populate(self, folder):
        for concurrency in (1, 4, 8):
            for round_number in (1, 2, 3):
                data = {
                    "schema_version": 1, "source": "instrumented_real_postgres_store",
                    "client_threads": concurrency,
                    "new": {"operations": 30, "elapsed_ms": 100,
                            "stages": {name: stage(30) for name in CREATE}},
                    "replay": {"operations": 15, "elapsed_ms": 100,
                               "stages": {name: stage(15) for name in REPLAY}},
                }
                (folder / f"phase-round-{round_number}-clients-{concurrency}.json").write_text(
                    json.dumps(data), encoding="utf-8")

    def test_complete_data_and_table(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            self.populate(folder)
            result = summarize(folder)
            self.assertEqual(result["results"]["8"]["median_create_lock_p95_ms"], 2.0)
            self.assertEqual(len(result["results"]["8"]["trials"]), 3)
            self.assertIn("| 8 |", markdown(result))
            self.assertIn("not HTTP request decomposition", markdown(result))

    def test_missing_report_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            self.populate(folder)
            (folder / "phase-round-3-clients-8.json").unlink()
            with self.assertRaises(FileNotFoundError):
                summarize(folder)

    def test_missing_phase_and_wrong_counts_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            self.populate(folder)
            path = folder / "phase-round-1-clients-4.json"
            data = json.loads(path.read_text(encoding="utf-8"))
            del data["new"]["stages"]["commit"]
            path.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaises(ValueError):
                summarize(folder)
            data["new"]["stages"]["commit"] = stage(29)
            path.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaises(ValueError):
                summarize(folder)

    def test_invalid_negative_and_nonfinite_timings_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            self.populate(folder)
            path = folder / "phase-round-2-clients-1.json"
            data = json.loads(path.read_text(encoding="utf-8"))
            data["new"]["stages"]["capacity_lock"]["p95_ms"] = -1
            path.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaises(ValueError):
                summarize(folder)
            data["new"]["stages"]["capacity_lock"]["p95_ms"] = float("nan")
            path.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaises(ValueError):
                summarize(folder)


if __name__ == "__main__":
    unittest.main()
