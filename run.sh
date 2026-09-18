#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p build/classes
javac --release 21 -d build/classes src/main/java/dev/asritha/intake/*.java
exec java -cp build/classes dev.asritha.intake.JobIntakeServer "${1:-8080}"
