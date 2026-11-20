#!/bin/sh
# Compile-checks all GensCore sources without Maven, into a private temp folder.
# Usage: sh check-compile.sh [grep-filter]
cd "$(dirname "$0")"
OUT=$(mktemp -d)
find src/main/java -name '*.java' > "$OUT/sources.txt"
"${JAVAC:-/c/Users/madgp/.jdks/openjdk-26.0.2.1/bin/javac}" --release 25 -proc:none -nowarn -encoding UTF-8 \
  -cp "$(cat .classpath.txt)" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | grep -E "${1:-.}" -A2 | head -80
rm -rf "$OUT"
