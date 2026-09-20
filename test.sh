#!/bin/sh
set -eu
cd "$(dirname "$0")"
./scripts/compile.sh
java -cp 'build/classes:build/deps/*' dev.asritha.intake.JobIntakeServerTest
java -cp 'build/classes:build/deps/*' dev.asritha.intake.HttpOperationsTest
if [ "${1:-}" = "--postgres" ]; then
  java -cp 'build/classes:build/deps/*' dev.asritha.intake.PostgresIntegrationTest
  java -cp 'build/classes:build/deps/*' dev.asritha.intake.WorkerIntegrationTest
elif [ "$#" -ne 0 ]; then
  echo 'Usage: ./test.sh [--postgres]' >&2
  exit 1
fi
