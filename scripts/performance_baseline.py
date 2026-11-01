#!/usr/bin/env python3
"""Bounded, opt-in local benchmark for the Job Intake Service HTTP contract.

This is a measurement client, not a load-test result or a production-safe load generator.
"""
import argparse
import concurrent.futures
import json
import math
import statistics
import sys
import time
import uuid
from collections import Counter
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def percentile(samples, fraction):
    if not samples:
        return None
    values = sorted(samples)
    return round(values[max(0, math.ceil(len(values) * fraction) - 1)], 3)


def validate_url(url):
    parsed = urlsplit(url)
    if (parsed.scheme != "http" or parsed.hostname not in ("localhost", "127.0.0.1")
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or parsed.path not in ("", "/") or not parsed.port):
        raise ValueError("Only an explicit loopback http://127.0.0.1:PORT base URL is permitted")
    return "http://127.0.0.1:" + str(parsed.port)


def request(method, url, key=None, payload=None, timeout=5):
    headers = {"Accept": "application/json"}
    data = None
    if payload is not None:
        data = payload.encode("utf-8")
        headers.update({"Content-Type": "text/plain", "Idempotency-Key": key})
    req = Request(url, data=data, headers=headers, method=method)
    started = time.perf_counter()
    try:
        with build_opener(NoRedirect()).open(req, timeout=timeout) as response:
            status, body, location = response.status, response.read(65536), response.headers.get("Location")
    except HTTPError as exc:
        status, body, location = exc.code, exc.read(65536), exc.headers.get("Location")
    except (OSError, URLError, TimeoutError) as exc:
        return {"status": "transport_error", "error": type(exc).__name__,
                "latency_ms": round((time.perf_counter() - started) * 1000, 3)}
    return {"status": status, "location": location,
            "body": body.decode("utf-8", errors="replace"),
            "latency_ms": round((time.perf_counter() - started) * 1000, 3)}


def phase(base, entries, workers, timeout, expected_status, known_locations=None):
    def send(entry):
        key, payload = entry
        response = request("POST", base + "/jobs", key, payload, timeout)
        response["key"] = key
        if known_locations is not None and response["status"] == 200:
            response["location_matches"] = response.get("location") == known_locations.get(key)
        return response

    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        results = list(executor.map(send, entries))
    seconds = max(time.perf_counter() - started, 1e-9)
    counts = Counter(str(r["status"]) for r in results)
    latencies = [r["latency_ms"] for r in results]
    mismatches = sum(r["status"] != expected_status for r in results)
    mismatches += sum(r.get("location_matches") is False for r in results)
    return {
        "requests": len(entries), "elapsed_seconds": round(seconds, 4),
        "requests_per_second": round(len(entries) / seconds, 3),
        "status_counts": dict(sorted(counts.items())),
        "latency_ms": {"p50": percentile(latencies, .5),
                       "p95": percentile(latencies, .95),
                       "p99": percentile(latencies, .99)},
        "unexpected_results": mismatches,
    }, results


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8080")
    parser.add_argument("--new-jobs", type=int, default=30)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--replay-percent", type=int, default=50)
    parser.add_argument("--timeout", type=float, default=5)
    parser.add_argument("--allow-writes", action="store_true",
                        help="Explicit opt-in: submits jobs and permanently consumes database capacity")
    parser.add_argument("--output", help="JSON report path; refuses overwrites")
    args = parser.parse_args(argv)
    try:
        base = validate_url(args.url)
        if not 1 <= args.new_jobs <= 200 or not 1 <= args.concurrency <= 32:
            raise ValueError("Limits: 1..200 new jobs and 1..32 concurrent clients")
        if not 0 <= args.replay_percent <= 100 or not 0 < args.timeout <= 10:
            raise ValueError("Limits: replay percent 0..100 and timeout (0,10] seconds")
        if not args.allow_writes:
            print("DRY RUN: no requests sent. Add --allow-writes only against an isolated test database.")
            print(f"Plan: {args.new_jobs} new jobs, {args.concurrency} clients, "
                  f"{args.replay_percent}% replay, target {base}")
            return 0
        ready = request("GET", base + "/ready", timeout=args.timeout)
        if ready["status"] != 200:
            raise ValueError("Readiness probe failed; benchmark did not submit jobs")
        run_id = uuid.uuid4().hex[:12]
        entries = [(f"bench-{run_id}-{index}", f"benchmark-payload-{index}") for index in range(args.new_jobs)]
        accepted, created = phase(base, entries, args.concurrency, args.timeout, 201)
        locations = {r["key"]: r.get("location") for r in created if r["status"] == 201}
        location_issues = sum(not (isinstance(loc, str) and loc.startswith("/jobs/"))
                              for loc in locations.values())
        accepted["unexpected_results"] += location_issues
        replay_count = args.new_jobs * args.replay_percent // 100
        # Replays intentionally run only for successfully accepted keys.
        replay_entries = [entry for entry in entries[:replay_count] if entry[0] in locations]
        replayed, _ = phase(base, replay_entries, args.concurrency, args.timeout, 200, locations)
        report = {
            "schema_version": 1,
            "method": "HTTP client round-trip; sequential create and replay phases",
            "limits": {"new_jobs": args.new_jobs, "concurrency": args.concurrency,
                       "replay_percent": args.replay_percent, "timeout_seconds": args.timeout},
            "target": base, "store_mode": "not_verified_by_client",
            "new": accepted, "replay": replayed,
            "notes": [
                "Each run permanently consumes job slots; use a disposable PostgreSQL database.",
                "No workers are started by this tool; other running workers can affect results.",
                "Client latency includes transport, scheduling and server time; no DB lock timing is measured.",
                "Fixed phase order and a single local host cannot establish capacity or production SLOs.",
                "Report omits payloads, keys and job IDs; run identifier intentionally not recorded."
            ],
        }
        output = json.dumps(report, indent=2, sort_keys=True) + "\n"
        if args.output:
            from pathlib import Path
            path = Path(args.output)
            with path.open("x", encoding="utf-8") as handle:
                handle.write(output)
        else:
            print(output, end="")
        return 0 if not (accepted["unexpected_results"] or replayed["unexpected_results"]) else 1
    except (ValueError, OSError) as error:
        print("benchmark error: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
