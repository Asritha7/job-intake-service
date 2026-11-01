#!/usr/bin/env bash
set -euo pipefail
if [[ "${GITHUB_ACTIONS:-}" != "true" || "${RUN_INTAKE_PERF_CI:-}" != "isolated-postgres" ||
      "${DATABASE_URL:-}" != "jdbc:postgresql://127.0.0.1:5432/jobs" ||
      "${DATABASE_USER:-}" != "jobs" ||
      "${DATABASE_PASSWORD:-}" != "isolated_ci_password" ||
      "${DATABASE_SCHEMA:-public}" != "public" ]]; then
  echo "Refusing pool experiment outside the disposable GitHub Actions database." >&2
  exit 2
fi
mkdir -p build/perf-deps .local/connection-compare
download_verified() {
  local url="$1" expected="$2" target="$3" tmp
  if [[ ! -e "$target" ]]; then
    tmp=$(mktemp build/perf-deps/verify.XXXXXX)
    if ! curl --fail --location --silent --show-error --max-time 30 "$url" -o "$tmp"; then
      rm -f "$tmp"
      return 1
    fi
    printf '%s  %s\n' "$expected" "$tmp" | sha256sum -c - >/dev/null
    mv "$tmp" "$target"
  fi
  printf '%s  %s\n' "$expected" "$target" | sha256sum -c - >/dev/null
}
download_verified \
  https://repo.maven.apache.org/maven2/com/zaxxer/HikariCP/6.3.3/HikariCP-6.3.3.jar \
  709f378c05756280939ce50fc1b1f1a53bb8e1899dc1b249f21f12703640b48b \
  build/perf-deps/HikariCP-6.3.3.jar
download_verified \
  https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar \
  7b751d952061954d5abfed7181c1f645d336091b679891591d63329c622eb832 \
  build/perf-deps/slf4j-api-2.0.17.jar

./scripts/compile.sh
javac --release 21 -cp 'build/classes:build/deps/*:build/perf-deps/*' -d build/classes \
  src/test/java/dev/asritha/intake/PoolComparisonProbe.java
java -cp 'build/classes:build/deps/*:build/perf-deps/*' \
  dev.asritha.intake.PoolComparisonProbeTest

./migrate.sh
# A fresh database is provided by GitHub Actions. Each of 27 trials creates 20
# jobs = 540 accepted jobs, within the 1,000-job limit. No deletion is performed.
for round in 1 2 3; do
  case "$round" in
    1) modes="fresh pool4 pool8";;
    2) modes="pool4 pool8 fresh";;
    3) modes="pool8 fresh pool4";;
  esac
  for clients in 1 4 8; do
    for mode in $modes; do
      java -cp 'build/classes:build/deps/*:build/perf-deps/*' \
        dev.asritha.intake.PoolComparisonProbe "$mode" "$clients" 20 \
        ".local/connection-compare/r${round}-c${clients}-${mode}.json"
    done
  done
done
python3 scripts/summarize_pool_comparison.py .local/connection-compare
