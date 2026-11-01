#!/usr/bin/env bash
set -euo pipefail

# This workflow receives a new disposable PostgreSQL service container.
if [[ "${GITHUB_ACTIONS:-}" != "true" ||
      "${RUN_INTAKE_PERF_CI:-}" != "isolated-postgres" ||
      "${DATABASE_URL:-}" != "jdbc:postgresql://127.0.0.1:5432/jobs" ||
      "${DATABASE_USER:-}" != "jobs" ||
      "${DATABASE_PASSWORD:-}" != "isolated_ci_password" ||
      "${DATABASE_SCHEMA:-public}" != "public" ]]; then
  echo "Refusing resilience test outside isolated GitHub Actions PostgreSQL." >&2
  exit 2
fi

# Use the existing checksum-verified Hikari and JDBC dependency bootstrap,
# without running the older 567-job performance experiment.
bash scripts/run_pool_comparison_ci.sh --dependencies-only
javac --release 21 -cp 'build/classes:build/deps/*:build/perf-deps/*' \
  -d build/classes src/perf/java/dev/asritha/intake/PoolReliabilityProbe.java

mkdir -p .local/pool-reliability
./migrate.sh
java -cp 'build/classes:build/deps/*:build/perf-deps/*' \
  dev.asritha.intake.PoolReliabilityProbe .local/pool-reliability/report.json
python3 scripts/validate_pool_reliability.py .local/pool-reliability/report.json
