#!/usr/bin/env python3
"""Fail closed on any missing or inconsistent isolated pool resilience outcome."""
import json
import sys
from pathlib import Path


EXPECTED = {
    "schema_version": 1,
    "source": "isolated_pool_resilience",
    "pool_exhaustion_and_release": True,
    "terminated_backend_recovered": True,
    "independent_jvms": 2,
    "pool_size_per_jvm": 2,
    "shared_key_creates": 1,
    "shared_key_replays": 15,
    "unique_creates": 4,
    "capacity_rejections": 4,
    "final_persisted_jobs": 5,
    "capacity": 5,
    "replay_at_capacity": True,
    "conflict_at_capacity": True,
}


def validate(report):
    if not isinstance(report, dict) or set(report) != set(EXPECTED):
        raise ValueError("Missing or unrecognized resilience report fields")
    for field, required in EXPECTED.items():
        value = report[field]
        if type(value) is not type(required) or value != required:
            raise ValueError(f"Incorrect or failed resilience invariant: {field}")
    return (
        "## Pool failure and independent-JVM acceptance checks\n\n"
        "Passed: two-connection exhaustion and recovery; terminated-backend replacement; "
        "two independent Java processes each using a two-connection pool; "
        "one durable shared-key creation plus 15 replays; "
        "four unique acceptances and four full-capacity rejections; "
        "stable replay and conflict responses at five-job capacity.\n\n"
        "**Limitations:** Disposable single-host PostgreSQL CI only; "
        "not network partitions, multi-host deployment, automatic production pool activation, "
        "sustained throughput or arbitrary external side-effect safety.\n"
    )


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: validate_pool_reliability.py REPORT.json")
    report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    summary = validate(report)
    print(summary)
    import os
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as file:
            file.write(summary)


if __name__ == "__main__":
    main()
