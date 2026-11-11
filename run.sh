#!/usr/bin/env bash
# Starts SuperJavendo. Usage: ./run.sh [ROM] [options]   (no ROM -> game library)
# Rebuilds (skipping tests) when sources are newer than the jar, then runs a private copy of the jar so that
# a later `mvn package` cannot break a running emulator (classes are loaded lazily from the jar).
set -euo pipefail
cd "$(dirname "$0")"

case "${1:-}" in
    -h|--help)
        cat <<'H'
Usage: ./run.sh [ROM] [options]
  (no args)           open the game library
  ROM                 run a ROM directly
  -p1 NAME -p2 NAME   controller profile per player (e.g. -p1 easysmx)
  --controllers       open the controller configuration
  --headless N --shot out.png   run N frames without a window and save a screenshot
H
        exit 0 ;;
esac

JAR=target/superjavendo.jar
RUN_JAR=target/superjavendo-run.jar

if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -newer "$JAR" -type f -print -quit)" ]; then
    echo "Building..." >&2
    mvn -q package -DskipTests
fi

cp -f "$JAR" "$RUN_JAR"
exec java -jar "$RUN_JAR" "$@"
