#!/bin/sh
set -eu
./scripts/dependencies.sh
mkdir -p build/classes
javac --release 21 -cp 'build/deps/*' -d build/classes src/main/java/dev/asritha/intake/*.java src/test/java/dev/asritha/intake/*.java
