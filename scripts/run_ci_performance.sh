#!/usr/bin/env bash
set -euo pipefail

# Runs only in a disposable GitHub Actions PostgreSQL service.
# Never invoke this script against an ordinary development database.
if [[ "${GITHUB_ACTIONS:-}" != "true" || "${RUN_INTAKE_PERF_CI:-}" != "isolated-postgres" ]]; then
  echo "Refusing benchmark outside explicitly configured isolated GitHub Actions job." >&2
  exit 2
fi
if [[ "${DATABASE_URL:-}" != "jdbc:postgresql://127.0.0.1:5432/jobs" || "${DATABASE_SCHEMA:-public}" != "public" ]]; then
  echo "Unexpected benchmark database endpoint/schema" >&2
  exit 2
fi

mkdir -p .local/performance-ci
./migrate.sh
./run.sh 8080 >.local/performance-ci/server.log 2>&1 &
server_pid=$!
cleanup() {
  kill "$server_pid" 2>/dev/null || true
  wait "$server_pid" 2>/dev/null || true
}
trap cleanup EXIT

ready=0
for attempt in $(seq 1 45); do
  if curl -fsS --max-time 2 http://127.0.0.1:8080/ready >/dev/null; then
    ready=1
    break
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "Server exited before readiness" >&2
    exit 1
  fi
  sleep 1
done
if [[ "$ready" != "1" ]]; then
  echo "Server failed readiness" >&2
  exit 1
fi

# One small untimed warmup. All runs use new keys; total accepted is under 300,
# below the service's default 1,000 lifetime-job capacity.
python3 scripts/performance_baseline.py --url http://127.0.0.1:8080 \
  --allow-writes --new-jobs 3 --concurrency 1 --replay-percent 100 \
  --output .local/performance-ci/warmup.json

# Rotate concurrency to avoid always assigning the fastest/coldest stage to one arm.
for round in 1 2 3; do
  case "$round" in
    1) order="1 4 8";;
    2) order="4 8 1";;
    3) order="8 1 4";;
  esac
  for clients in $order; do
    python3 scripts/performance_baseline.py --url http://127.0.0.1:8080 \
      --allow-writes --new-jobs 30 --concurrency "$clients" --replay-percent 50 \
      --output ".local/performance-ci/round-${round}-clients-${clients}.json"
  done
done

python3 scripts/summarize_performance_ci.py .local/performance-ci
