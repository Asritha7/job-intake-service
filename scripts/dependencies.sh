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

# Runtime jars are pinned and verified even when pooling is disabled, keeping
# the standard JDK-only compilation path reproducible and fail-closed.
verify_optional_runtime_jar() {
  artifact="$1"
  expected="$2"
  url="$3"
  if [ ! -f "$artifact" ]; then
    temporary=$(mktemp build/deps/download.XXXXXX)
    trap 'rm -f "$temporary"' EXIT HUP INT TERM
    curl --fail --location --silent --show-error "$url" -o "$temporary"
    printf '%s  %s\n' "$expected" "$temporary" | shasum -a 256 -c - >/dev/null
    mv "$temporary" "$artifact"
    trap - EXIT HUP INT TERM
  fi
  printf '%s  %s\n' "$expected" "$artifact" | shasum -a 256 -c - >/dev/null
}
verify_optional_runtime_jar build/deps/HikariCP-6.3.3.jar \
  709f378c05756280939ce50fc1b1f1a53bb8e1899dc1b249f21f12703640b48b \
  https://repo.maven.apache.org/maven2/com/zaxxer/HikariCP/6.3.3/HikariCP-6.3.3.jar
verify_optional_runtime_jar build/deps/slf4j-api-2.0.17.jar \
  7b751d952061954d5abfed7181c1f645d336091b679891591d63329c622eb832 \
  https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar
