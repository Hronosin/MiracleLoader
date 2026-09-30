#!/usr/bin/env bash
# Installs MiracleLoader into a Prism Launcher instance, with the example mods as a smoke test.
#
#   ./prism-install.sh "My 26.1 Instance"          install
#   ./prism-install.sh --uninstall "My Instance"   remove it again
#   ./prism-install.sh --list                      show instances
#
# Close Prism first: it rewrites mmc-pack.json on exit and would undo the changes. The work is
# done by `miracle consecrate` (so Windows has it too: prism-install.cmd); PRISM_DATA=... or
# --prism "folder" if Prism's data isn't in the usual place.
set -euo pipefail
cd "$(dirname "$0")"
[ -f build/miracle.jar ] && [ -f build/title-mod.jar ] || ./build.sh
case "${1:-}" in
    --list|--uninstall) extra=() ;;
    *) extra=(--examples) ;;
esac
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar build/miracle.jar consecrate ${extra[@]+"${extra[@]}"} "$@"
