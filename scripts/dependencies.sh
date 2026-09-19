#!/bin/sh
set -eu
mkdir -p build/deps
jar=build/deps/postgresql-42.7.13.jar
checksum=6e0e4cc2d8cae902084f8a2b18728b073a6fd9d1f87c9d8bff8f298c18185b93
if [ ! -f "$jar" ]; then
  temporary=$(mktemp build/deps/download.XXXXXX)
  trap 'rm -f "$temporary"' EXIT HUP INT TERM
  curl --fail --location --silent --show-error https://repo.maven.apache.org/maven2/org/postgresql/postgresql/42.7.13/postgresql-42.7.13.jar -o "$temporary"
  printf '%s  %s\n' "$checksum" "$temporary" | shasum -a 256 -c -
  mv "$temporary" "$jar"
fi
printf '%s  %s\n' "$checksum" "$jar" | shasum -a 256 -c - >/dev/null
