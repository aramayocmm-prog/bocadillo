#!/usr/bin/env bash
# Compiles and tests the pure-Kotlin modules (:core:model, :core:imaging) WITHOUT network or
# Android SDK, using the Kotlin compiler bundled with a local Gradle installation.
# Usage: tools/build-core-offline.sh [/path/to/gradle/lib]
# The normal way to build is `./gradlew :core:model:test :core:imaging:test` (needs network once).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
L="${1:-/opt/gradle-8.14.3/lib}"
OUT="${OUT:-$ROOT/build/offline}"
rm -rf "$OUT"; mkdir -p "$OUT"
STD="$L/kotlin-stdlib-2.0.21.jar"
COMPILER_CP="$L/kotlin-compiler-embeddable-2.0.21.jar:$STD:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-24.0.1.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar"
SER="$L/kotlinx-serialization-core-jvm-1.6.2.jar:$L/kotlinx-serialization-json-jvm-1.6.2.jar"
JUNIT="$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar"

kc() { java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 "$@" 2>&1 | grep -v "Picked up JAVA_TOOL_OPTIONS" || true; }

echo "== compile core:model"
kc -cp "$STD:$SER" -d "$OUT/model" $(find "$ROOT/core/model/src/main" -name '*.kt')
echo "== compile core:imaging"
kc -cp "$STD:$SER:$OUT/model" -d "$OUT/imaging" $(find "$ROOT/core/imaging/src/main" -name '*.kt')
echo "== compile tests"
kc -cp "$STD:$SER:$JUNIT:$OUT/model:$OUT/imaging" -Xfriend-paths="$OUT/model,$OUT/imaging" -d "$OUT/tests" \
   $(find "$ROOT/core/model/src/test" "$ROOT/core/imaging/src/test" -name '*.kt')
[ -d "$OUT/model" ] && [ -d "$OUT/imaging" ] && [ -d "$OUT/tests" ] || { echo "COMPILATION FAILED"; exit 1; }

CLASSES=$(cd "$OUT/tests" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
echo "== run tests: $(echo $CLASSES | wc -w) classes"
java -Dbocadillo.visualOut="${VISUAL_OUT:-$ROOT/build/visual}" \
     -cp "$STD:$SER:$JUNIT:$OUT/model:$OUT/imaging:$OUT/tests" org.junit.runner.JUnitCore $CLASSES 2>&1 \
     | grep -v "Picked up JAVA_TOOL_OPTIONS"
