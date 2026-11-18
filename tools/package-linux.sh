#!/usr/bin/env bash
# Builds a Linux app image with its own reduced Java runtime (no Java needed on the target machine).
# Output: dist/SuperJavendo/bin/SuperJavendo   (dist/ is ignored by git; dist/work holds the intermediate files)
#
#   tools/package-linux.sh              runtime with the modules found by jdeps + locale data (number/date formats of all languages)
#   tools/package-linux.sh --no-locales leave jdk.localedata out (about 11 MB less; non-English numbers and dates use the base format)
#
# jpackage cannot cross-compile: run this on Linux for Linux (the same idea on Windows/macOS needs that system).
# For a release use a JDK 25 (the project's target); the runtime in the image is the one of the JDK that runs this script.
set -euo pipefail
cd "$(dirname "$0")/.."

LOCALES=jdk.localedata
[[ "${1:-}" == "--no-locales" ]] && LOCALES=""

for tool in jdeps jlink jpackage; do
    command -v "$tool" >/dev/null || { echo "$tool not found: put a JDK (not a JRE) in PATH" >&2; exit 1; }
done

JAR=target/superjavendo.jar
if [[ ! -f "$JAR" ]]; then mvn -q package -DskipTests; fi

rm -rf dist
mkdir -p dist/work/input
cp "$JAR" dist/work/input/

MODULES=$(jdeps --print-module-deps --ignore-missing-deps --multi-release 25 "$JAR")
[[ -n "$LOCALES" ]] && MODULES="$MODULES,$LOCALES"
echo "Modules: $MODULES"

jlink --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages --compress zip-6 --output dist/work/runtime

# --enable-native-access: the gamepad backend (input4j) uses the Foreign Function API; the jar manifest alone is not read by this launcher
jpackage --type app-image --name SuperJavendo --app-version 1.0 \
    --input dist/work/input --main-jar superjavendo.jar --main-class dev.superjavendo.Main \
    --runtime-image dist/work/runtime --java-options "--enable-native-access=ALL-UNNAMED" \
    --dest dist

du -sh dist/SuperJavendo
echo "Run: dist/SuperJavendo/bin/SuperJavendo [ROM]"
