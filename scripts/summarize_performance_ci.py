#!/usr/bin/env python3
"""Summarize repeated isolated PostgreSQL CI measurements without inventing capacity claims."""
import json
import platform
import statistics
import sys
from pathlib import Path


def summarize(folder):
    result = {}
    for clients in (1, 4, 8):
        trials = []
        for round_number in (1, 2, 3):
            file = folder / f"round-{round_number}-clients-{clients}.json"
            report = json.loads(file.read_text(encoding="utf-8"))
            if report["limits"]["concurrency"] != clients or report["limits"]["new_jobs"] != 30:
                raise ValueError(f"Wrong workload in {file.name}")
            created, replay = report["new"], report["replay"]
            if (created["requests"] != 30 or replay["requests"] != 15
                    or created["unexpected_results"] or replay["unexpected_results"]
                    or created["status_counts"] != {"201": 30}
                    or replay["status_counts"] != {"200": 15}):
                raise ValueError(f"Job contract failed under load in {file.name}")
            trials.append({
                "round": round_number,
                "accepts_per_second": created["requests_per_second"],
                "accept_p50_ms": created["latency_ms"]["p50"],
                "accept_p95_ms": created["latency_ms"]["p95"],
                "accept_p99_ms": created["latency_ms"]["p99"],
                "replays_per_second": replay["requests_per_second"],
                "replay_p95_ms": replay["latency_ms"]["p95"],
            })
        result[str(clients)] = {
            "trials": trials,
            "median_accepts_per_second": statistics.median(t["accepts_per_second"] for t in trials),
            "range_accepts_per_second": [min(t["accepts_per_second"] for t in trials),
                                          max(t["accepts_per_second"] for t in trials)],
            "median_trial_p95_ms": statistics.median(t["accept_p95_ms"] for t in trials),
        }
    return {
        "schema_version": 1,
        "dataset": "isolated_github_actions_postgres_baseline_not_production",
        "runtime": {"python": platform.python_version(), "platform": platform.platform()},
        "workload": {"new_jobs_per_trial": 30, "replay_jobs_per_trial": 15,
                     "rounds": 3, "client_concurrency": [1, 4, 8],
                     "worker_processes": 0, "api_processes": 1,
                     "database": "fresh_postgresql_17_11_service"},
        "results": result,
        "limitations": [
            "CI is a noisy shared environment: rates and latencies are observations, not service capacity or SLAs.",
            "Runs use one database with accumulating job history; rotate arm order but do not treat arms as isolated databases.",
            "The client measures HTTP round trips, not server CPU, database lock waits, or worker throughput.",
            "p95 values summarize individual trials; they are not a pooled latency percentile.",
            "No admission algorithm was changed and no speedup is claimed.",
        ],
    }


def to_markdown(summary):
    lines = [
        "## Job Intake Service — isolated PostgreSQL baseline",
        "",
        "Three repetitions per client-concurrency setting on a disposable GitHub Actions PostgreSQL service.",
        "",
        "| Client concurrency | Median create requests/s | Observed range | Median per-trial create p95 (ms) |",
        "| ---: | ---: | ---: | ---: |",
    ]
    for count in ("1", "4", "8"):
        data = summary["results"][count]
        lines.append(f"| {count} | {data['median_accepts_per_second']:.2f} "
                     f"| {data['range_accepts_per_second'][0]:.2f}–{data['range_accepts_per_second'][1]:.2f} "
                     f"| {data['median_trial_p95_ms']:.2f} |")
    lines.extend(["", "**Interpretation:** CI-local, small-sample measurements only. "
                  "No production throughput, worker capacity or database-lock bottleneck is established.",
                  "See the JSON artifact for all trials and methodology.", ""])
    return "\n".join(lines)


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: summarize_performance_ci.py REPORT_DIRECTORY")
    folder = Path(sys.argv[1])
    summary = summarize(folder)
    target = folder / "summary.json"
    target.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    markdown = to_markdown(summary)
    (folder / "summary.md").write_text(markdown, encoding="utf-8")
    print(markdown)
    import os
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
            output.write(markdown)


if __name__ == "__main__":
    main()
