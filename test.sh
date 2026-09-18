#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p build/classes
javac --release 21 -d build/classes src/main/java/dev/asritha/intake/*.java src/test/java/dev/asritha/intake/*.java
java -cp build/classes dev.asritha.intake.JobIntakeServerTest
