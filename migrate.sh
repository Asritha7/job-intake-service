#!/bin/sh
set -eu
cd "$(dirname "$0")"
./scripts/compile.sh
exec java -cp 'build/classes:build/deps/*' dev.asritha.intake.Migrate
