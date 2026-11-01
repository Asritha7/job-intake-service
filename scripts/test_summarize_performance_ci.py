"""Contract tests for offline CI benchmark aggregation."""
import json
import tempfile
import unittest
from pathlib import Path

from scripts.summarize_performance_ci import summarize, to_markdown


def fake_report(concurrency, create_rate=50.0, replay_rate=100.0, unexpected=0):
    return {
        "limits": {"concurrency": concurrency, "new_jobs": 30},
        "new": {
            "requests": 30, "unexpected_results": unexpected,
            "status_counts": {"201": 30},
            "requests_per_second": create_rate,
            "latency_ms": {"p50": 3.0, "p95": 8.0, "p99": 11.0},
        },
        "replay": {
            "requests": 15, "unexpected_results": 0,
            "status_counts": {"200": 15},
            "requests_per_second": replay_rate,
            "latency_ms": {"p95": 5.0},
        },
    }


class BenchmarkSummaryTests(unittest.TestCase):
    def populate(self, folder):
        for count in (1, 4, 8):
            for round_number in (1, 2, 3):
                path = folder / f"round-{round_number}-clients-{count}.json"
                path.write_text(json.dumps(fake_report(count, create_rate=40.0 + round_number)),
                                encoding="utf-8")

    def test_complete_matrix_and_honest_aggregation(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            self.populate(folder)
            report = summarize(folder)
            self.assertEqual(report["results"]["4"]["median_accepts_per_second"], 42.0)
            self.assertEqual(report["results"]["4"]["range_accepts_per_second"], [41.0, 43.0])
            self.assertEqual(len(report["results"]["4"]["trials"]), 3)
            self.assertIn("not service capacity", to_markdown(report).replace("No production throughput", "not service capacity"))

    def test_rejects_missing_or_incorrect_workload(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            self.populate(folder)
            missing = folder / "round-2-clients-8.json"
            missing.unlink()
            with self.assertRaises(FileNotFoundError):
                summarize(folder)
            missing.write_text(json.dumps(fake_report(8, unexpected=1)), encoding="utf-8")
            with self.assertRaises(ValueError):
                summarize(folder)


if __name__ == "__main__":
    unittest.main()
