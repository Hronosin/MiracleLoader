#!/usr/bin/env bash
# MiracleLoader build. No Gradle, no Maven, no Loom: javac and jar, as promised.
# The build itself is tools/build/Build.java, so Windows (build.cmd) and macOS run the same one.
set -euo pipefail
cd "$(dirname "$0")"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
spec="$("$JAVA" -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p')"
case "$spec" in
    ""|1.*|[0-9]|10) echo "Need a JDK 25 or newer, found: $("$JAVA" -version 2>&1 | head -1)" >&2
                     echo "Fedora: sudo dnf install java-25-openjdk-devel; or adoptium.net" >&2
                     exit 1 ;;
esac
exec "$JAVA" tools/build/Build.java "$@"
