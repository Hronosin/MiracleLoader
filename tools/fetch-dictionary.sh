#!/usr/bin/env bash
# Fetches Minecraft versions' dictionaries for miracle-bake: the client jar, plus Mojang's
# official mappings where the version is obfuscated. Takes versions or patterns:
#
#   tools/fetch-dictionary.sh 1.21.11
#   tools/fetch-dictionary.sh "26.*" ">=1.21.11" latest
#
# build.sh and `miracle bake` do this by themselves now; this stays for scripts. The mappings
# are for development use only: they stay in your cache and never go into a mod.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f build/miracle.jar ] || ./build.sh > /dev/null
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar build/miracle.jar dictionary "$@"
