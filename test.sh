#!/bin/sh
set -eu
cd "$(dirname "$0")"
./scripts/compile.sh
java -cp 'build/classes:build/deps/*' dev.asritha.intake.JobIntakeServerTest
if [ "${1:-}" = "--postgres" ]; then
  java -cp 'build/classes:build/deps/*' dev.asritha.intake.PostgresIntegrationTest
elif [ "$#" -ne 0 ]; then
  echo 'Usage: ./test.sh [--postgres]' >&2
  exit 1
fi
