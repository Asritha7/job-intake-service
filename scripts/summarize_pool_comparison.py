#!/usr/bin/env python3
"""Validate and summarize the bounded direct-store JDBC connection comparison."""
import json
import math
import statistics
import sys
from pathlib import Path

MODES = ("fresh", "pool4", "pool8")
CLIENTS = (1, 4, 8)


def valid_positive(value):
    return type(value) in (int, float) and math.isfinite(value) and value > 0


def check_phase(phase, expected, mode, filename):
    if phase.get("operations") != expected:
        raise ValueError(f"Incorrect operation count: {filename}")
    for key in ("p95_ms", "throughput_per_second"):
        if not valid_positive(phase.get(key)):
            raise ValueError(f"Invalid {key}: {filename}")
    metrics = phase.get("phase_metrics", {})
    if metrics.get("operations") != expected:
        raise ValueError(f"Invalid recorder count: {filename}")
    stages = metrics.get("stages", {})
    required = {"jdbc_connect", "capacity_lock", "key_lookup", "commit"}
    if phase is not None and expected == 20:
        required |= {"capacity_count", "insert"}
    if set(stages) != required:
        raise ValueError(f"Unexpected stages in {filename}")
    for name, stage in stages.items():
        if stage.get("count") != expected:
            raise ValueError(f"Missing stage observations {name}: {filename}")
        for key in ("p50_ms", "p95_ms", "sum_ms"):
            value = stage.get(key)
            if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
                raise ValueError(f"Invalid stage timing in {filename}")
        if stage["p95_ms"] < stage["p50_ms"]:
            raise ValueError(f"Invalid percentile ordering in {filename}")


def summarize(folder):
    results = {}
    for clients in CLIENTS:
        per_mode = {}
        for mode in MODES:
            observations = []
            for round_number in (1, 2, 3):
                file = folder / f"r{round_number}-c{clients}-{mode}.json"
                item = json.loads(file.read_text(encoding="utf-8"))
                if (item.get("schema_version") != 1
                        or item.get("source") != "isolated_connection_comparison"
                        or item.get("mode") != mode
                        or item.get("clients") != clients
                        or item.get("pool_size") != (0 if mode == "fresh" else int(mode[-1]))
                        or item.get("conflict_check") is not True
                        or item.get("transaction_state_check") is not True
                        or item.get("sql_rollback_check") is not True
                        or item.get("id_stability_check") is not True
                        or item.get("persisted_count_check") is not True):
                    raise ValueError(f"Invalid benchmark provenance / integrity: {file.name}")
                check_phase(item.get("create", {}), 20, mode, file.name)
                check_phase(item.get("replay", {}), 10, mode, file.name)
                observations.append(item)
            per_mode[mode] = {
                "trials": observations,
                "median_create_throughput": statistics.median(t["create"]["throughput_per_second"] for t in observations),
                "median_create_p95_ms": statistics.median(t["create"]["p95_ms"] for t in observations),
                "median_acquire_p95_ms": statistics.median(t["create"]["phase_metrics"]["stages"]["jdbc_connect"]["p95_ms"] for t in observations),
                "median_lock_p95_ms": statistics.median(t["create"]["phase_metrics"]["stages"]["capacity_lock"]["p95_ms"] for t in observations),
            }
        results[str(clients)] = per_mode
    return {
        "schema_version": 1,
        "method": "direct PostgresJobStore.submit comparison; same disposable PostgreSQL service",
        "workload": {"rounds": 3, "jobs_per_trial": 20, "replays_per_trial": 10,
                     "clients": list(CLIENTS), "modes": list(MODES), "total_new_jobs": 540},
        "results": results,
        "limitations": [
            "Each mode observes an accumulating database; rotated mode order reduces but does not eliminate bias.",
            "New Java process per trial; pooled trials prewarm their physical connections before measurement.",
            "Fresh mode opens one connection per call; the pool bounds active database connections to four or eight.",
            "Direct-store measurements, not end-to-end HTTP or sustained production throughput.",
            "Timing observer adds overhead; CI hardware and database conditions vary.",
            "Changing connection concurrency can change lock competition: improvements must be evaluated alongside capacity/identity correctness.",
        ],
    }


def markdown(result):
    lines = [
        "## Isolated JDBC connection-strategy comparison",
        "",
        "Three trials per strategy and concurrency setting; 20 creates, 10 replays per trial.",
        "",
        "| Clients | Mode | Median create req/s | Median create p95 ms | Median acquisition p95 ms | Median lock-statement p95 ms |",
        "| ---: | --- | ---: | ---: | ---: | ---: |",
    ]
    for clients in CLIENTS:
        for mode in MODES:
            row = result["results"][str(clients)][mode]
            lines.append(f"| {clients} | {mode} | {row['median_create_throughput']:.2f} "
                         f"| {row['median_create_p95_ms']:.2f} "
                         f"| {row['median_acquire_p95_ms']:.2f} "
                         f"| {row['median_lock_p95_ms']:.2f} |")
    lines.extend(["", "**Interpretation:** exploratory, instrumentation-affected CI direct-store results only. "
                  "No deployment decision, causal speedup attribution, or production SLO is justified without "
                  "repeatable capacity and failure-mode comparisons.", ""])
    return "\n".join(lines)


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: summarize_pool_comparison.py REPORT_FOLDER")
    folder = Path(sys.argv[1])
    summary = summarize(folder)
    with (folder / "summary.json").open("x", encoding="utf-8") as out:
        json.dump(summary, out, sort_keys=True, indent=2)
        out.write("\n")
    body = markdown(summary)
    with (folder / "summary.md").open("x", encoding="utf-8") as out:
        out.write(body)
    print(body)
    import os
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as out:
            out.write(body)


if __name__ == "__main__":
    main()
