#!/usr/bin/env python3
"""Validate and summarize phase timings from direct PostgreSQL store experiments."""
import json
import math
import statistics
import sys
from pathlib import Path

CREATE = ("jdbc_connect", "capacity_lock", "key_lookup", "capacity_count", "insert", "commit")
REPLAY = ("jdbc_connect", "capacity_lock", "key_lookup", "commit")


def validate_stage(stage, expected_count):
    if set(stage) != {"count", "p50_ms", "p95_ms", "sum_ms"}:
        raise ValueError("Unexpected timing fields")
    if type(stage["count"]) is not int or stage["count"] != expected_count:
        raise ValueError("Missing timing samples")
    if any(not isinstance(stage[field], (float, int))
           or not math.isfinite(stage[field]) or stage[field] < 0
           for field in ("p50_ms", "p95_ms", "sum_ms")):
        raise ValueError("Invalid stage timing")
    if stage["p95_ms"] < stage["p50_ms"]:
        raise ValueError("Invalid percentile ordering")


def summarize(folder):
    results = {}
    for clients in (1, 4, 8):
        trials = []
        for round_number in (1, 2, 3):
            file = folder / f"phase-round-{round_number}-clients-{clients}.json"
            data = json.loads(file.read_text(encoding="utf-8"))
            if (data.get("schema_version") != 1 or data.get("source") != "instrumented_real_postgres_store"
                    or data.get("client_threads") != clients):
                raise ValueError(f"Unexpected provenance: {file.name}")
            row = {}
            for category, names, count in (("new", CREATE, 30), ("replay", REPLAY, 15)):
                phase = data[category]
                if phase["operations"] != count or set(phase["stages"]) != set(names):
                    raise ValueError(f"Missing/inconsistent phase data: {file.name}")
                if not isinstance(phase["elapsed_ms"], (int, float)) or not math.isfinite(phase["elapsed_ms"]) or phase["elapsed_ms"] <= 0:
                    raise ValueError(f"Invalid total elapsed: {file.name}")
                for stage_name in names:
                    validate_stage(phase["stages"][stage_name], count)
                row[category] = phase
            trials.append(row)
        results[str(clients)] = {
            "trials": trials,
            "median_create_lock_p95_ms": statistics.median(t["new"]["stages"]["capacity_lock"]["p95_ms"] for t in trials),
            "median_create_connection_p95_ms": statistics.median(t["new"]["stages"]["jdbc_connect"]["p95_ms"] for t in trials),
            "median_create_capacity_count_p95_ms": statistics.median(t["new"]["stages"]["capacity_count"]["p95_ms"] for t in trials),
            "median_create_commit_p95_ms": statistics.median(t["new"]["stages"]["commit"]["p95_ms"] for t in trials),
            "median_replay_lock_p95_ms": statistics.median(t["replay"]["stages"]["capacity_lock"]["p95_ms"] for t in trials),
        }
    return {
        "schema_version": 1,
        "method": "instrumented direct PostgresJobStore.submit, not end-to-end HTTP",
        "results": results,
        "limitations": [
            "The optional observer is called on the application path; its overhead affects the result.",
            "Each phase includes Java/JDBC work around its SQL; no exact PostgreSQL server-only lock duration is measured.",
            "The phase probe is independent of HTTP trials and runs later on a growing disposable database.",
            "Each reported p95 is from 30 or 15 operations; the median across three trials is not a pooled p95.",
            "No comparison to a competing algorithm or production capacity claim is supported.",
        ],
    }


def markdown(summary):
    lines = ["## Direct PostgreSQL submission-phase timings", "",
             "Test-only observer; three repetitions per client setting, 30 creates and 15 replays per trial.",
             "",
             "| Clients | Create lock p95 (ms) | Connection p95 (ms) | Capacity-count p95 (ms) | Replay lock p95 (ms) |",
             "| ---: | ---: | ---: | ---: | ---: |"]
    for clients in ("1", "4", "8"):
        row = summary["results"][clients]
        lines.append(f"| {clients} | {row['median_create_lock_p95_ms']:.2f} "
                     f"| {row['median_create_connection_p95_ms']:.2f} "
                     f"| {row['median_create_capacity_count_p95_ms']:.2f} "
                     f"| {row['median_replay_lock_p95_ms']:.2f} |")
    lines.extend(["", "These are direct-store, instrumentation-affected CI observations, **not HTTP request decomposition** or server-only wait durations.", ""])
    return "\n".join(lines)


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: summarize_admission_phases.py OUTPUT_DIRECTORY")
    folder = Path(sys.argv[1])
    report = summarize(folder)
    with (folder / "phase-summary.json").open("x", encoding="utf-8") as out:
        json.dump(report, out, indent=2, sort_keys=True)
        out.write("\n")
    md = markdown(report)
    (folder / "phase-summary.md").write_text(md, encoding="utf-8")
    print(md)
    import os
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as file:
            file.write(md)


if __name__ == "__main__":
    main()
