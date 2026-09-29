#!/usr/bin/env bash
# Downloads a Minecraft version's dictionary for miracle-bake, straight from Mojang:
#   the client jar, plus the official mappings if that version is obfuscated.
#
#   tools/fetch-dictionary.sh 1.21.11        -> ~/.cache/miracle/dictionaries/1.21.11/
#   tools/fetch-dictionary.sh 26.2
#
# Mojang's mappings are for development use only and must not be redistributed, so they stay
# in your cache: miracle-bake reads them, it never copies them into a mod.
set -euo pipefail

version="${1:?usage: $0 MINECRAFT_VERSION}"
root="${MIRACLE_DICTIONARIES:-$HOME/.cache/miracle/dictionaries}"
dir="$root/$version"
mkdir -p "$dir"

manifest="https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
url="$(curl -fsS "$manifest" | python3 -c "
import json, sys
m = json.load(sys.stdin)
v = [x for x in m['versions'] if x['id'] == sys.argv[1]]
print(v[0]['url'] if v else '')" "$version")"
[ -n "$url" ] || { echo "No Minecraft version called '$version'." >&2; exit 1; }

curl -fsS "$url" -o "$dir/version.json"
read -r client mappings < <(python3 -c "
import json, sys
d = json.load(open(sys.argv[1]))['downloads']
print(d['client']['url'], d.get('client_mappings', {}).get('url', '-'))" "$dir/version.json")

echo "Fetching Minecraft $version client..."
curl -fsS "$client" -o "$dir/client.jar"
if [ "$mappings" != "-" ]; then
    echo "Fetching Mojang's official mappings for $version..."
    curl -fsS "$mappings" -o "$dir/mappings.txt"
    echo "$version: obfuscated, dictionary ready in $dir"
else
    rm -f "$dir/mappings.txt"
    echo "$version: unobfuscated, the jar is its own dictionary ($dir)"
fi
