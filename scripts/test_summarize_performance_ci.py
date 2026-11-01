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
                (folder / f"round-{round_number}-clients-{count}.wait.json").write_text(
                    json.dumps({"source": "pg_stat_activity_sampling", "benchmark_exit_code": 0,
                                "sampler_errors": 0, "sample_rounds": 30,
                                "backend_observations": 45,
                                "observations": {"capacity_lock_wait": count,
                                                 "not_waiting": 45 - count}}),
                    encoding="utf-8")

    def test_complete_matrix_and_honest_aggregation(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            self.populate(folder)
            report = summarize(folder)
            self.assertEqual(report["results"]["4"]["median_accepts_per_second"], 42.0)
            self.assertEqual(report["results"]["4"]["range_accepts_per_second"], [41.0, 43.0])
            self.assertEqual(len(report["results"]["4"]["trials"]), 3)
            self.assertEqual(report["results"]["4"]["total_capacity_lock_wait_observations"], 12)
            self.assertIn("No production throughput", to_markdown(report))

    def test_rejects_sampler_failure_and_missing_wait_data(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            self.populate(folder)
            wait_path = folder / "round-2-clients-4.wait.json"
            wait_path.unlink()
            with self.assertRaises(FileNotFoundError):
                summarize(folder)
            wait_path.write_text(json.dumps({"source": "pg_stat_activity_sampling",
                                            "sampler_errors": 1, "sample_rounds": 5,
                                            "benchmark_exit_code": 0, "observations": {}}),
                                 encoding="utf-8")
            with self.assertRaises(ValueError):
                summarize(folder)

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
