#!/usr/bin/env bash
# Runs the fake game through MiracleLoader with the example mods.
set -euo pipefail
cd "$(dirname "$0")"
[ -f build/miracle-loader.jar ] || ./build.sh

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
RUN=build/run
rm -rf "$RUN" && mkdir -p "$RUN/mods"
cp build/mods/*.jar "$RUN/mods/"

cd "$RUN"
exec "$JAVA" -cp ../miracle-loader.jar:../fake-minecraft.jar \
    io.github.hronosin.miracle.MiracleMain --username Steve "$@"
