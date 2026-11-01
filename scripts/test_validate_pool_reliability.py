"""Offline contract tests for strict isolated resilience report validation."""
import unittest
from scripts.validate_pool_reliability import EXPECTED, validate


class PoolReliabilityReportTests(unittest.TestCase):
    def test_complete_invariants(self):
        summary = validate(EXPECTED.copy())
        self.assertIn("two independent Java processes", summary)
        self.assertIn("not network partitions", summary)

    def test_missing_field_rejected(self):
        report = EXPECTED.copy()
        del report["terminated_backend_recovered"]
        with self.assertRaises(ValueError):
            validate(report)

    def test_false_or_wrong_type_rejected(self):
        for name, value in (
            ("pool_exhaustion_and_release", False),
            ("independent_jvms", "2"),
            ("shared_key_creates", 2),
            ("capacity_rejections", 5),
            ("final_persisted_jobs", 6),
            ("replay_at_capacity", False),
        ):
            with self.subTest(name=name):
                report = EXPECTED.copy()
                report[name] = value
                with self.assertRaises(ValueError):
                    validate(report)

    def test_unknown_field_rejected(self):
        report = EXPECTED.copy()
        report["unverified"] = True
        with self.assertRaises(ValueError):
            validate(report)


if __name__ == "__main__":
    unittest.main()
