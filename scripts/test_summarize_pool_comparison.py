"""Verify isolated connection comparison report schema and failure gating."""
import json
import tempfile
import unittest
from pathlib import Path
from scripts.summarize_pool_comparison import summarize, markdown

CREATE = ("jdbc_connect", "capacity_lock", "key_lookup", "capacity_count", "insert", "commit")
REPLAY = ("jdbc_connect", "capacity_lock", "key_lookup", "commit")


def phase(expected, required):
    return {
        "operations": expected, "p95_ms": 3.5, "throughput_per_second": 120.0,
        "phase_metrics": {
            "operations": expected,
            "stages": {name: {"count": expected, "p50_ms": 0.2,
                              "p95_ms": 0.5, "sum_ms": expected * .35}
                       for name in required},
        },
    }


class ComparisonSummaryTest(unittest.TestCase):
    def fixtures(self, directory):
        for clients in (1, 4, 8):
            for mode in ("fresh", "pool4", "pool8"):
                for round_number in (1, 2, 3):
                    data = {
                        "schema_version": 1,
                        "source": "isolated_connection_comparison",
                        "mode": mode, "pool_size": 0 if mode == "fresh" else int(mode[-1]),
                        "clients": clients, "conflict_check": True, "transaction_state_check": True,
                        "create": phase(20, CREATE), "replay": phase(10, REPLAY),
                    }
                    (directory / f"r{round_number}-c{clients}-{mode}.json").write_text(
                        json.dumps(data), encoding="utf-8")

    def test_complete_matrix_and_summary(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            self.fixtures(folder)
            result = summarize(folder)
            self.assertEqual(result["results"]["4"]["pool4"]["median_create_throughput"], 120.0)
            self.assertIn("| 8 | pool8 |", markdown(result))
            self.assertEqual(result["workload"]["total_new_jobs"], 540)

    def test_missing_trial_or_wrong_integrity_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            self.fixtures(folder)
            filename = folder / "r2-c4-pool8.json"
            filename.unlink()
            with self.assertRaises(FileNotFoundError):
                summarize(folder)
            self.fixtures(folder)
            data = json.loads(filename.read_text(encoding="utf-8"))
            data["transaction_state_check"] = False
            filename.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaises(ValueError):
                summarize(folder)

    def test_missing_phase_and_bad_latency_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            self.fixtures(folder)
            filename = folder / "r3-c8-fresh.json"
            data = json.loads(filename.read_text(encoding="utf-8"))
            del data["create"]["phase_metrics"]["stages"]["commit"]
            filename.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaises(ValueError):
                summarize(folder)
            self.fixtures(folder)
            data = json.loads(filename.read_text(encoding="utf-8"))
            data["create"]["p95_ms"] = float("nan")
            filename.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaises(ValueError):
                summarize(folder)


if __name__ == "__main__":
    unittest.main()
