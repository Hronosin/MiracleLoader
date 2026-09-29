#!/usr/bin/env bash
# Installs MiracleLoader into a Prism Launcher instance as a custom component.
#
#   ./prism-install.sh "My 26.1 Instance"          install (+ title-mod as a smoke test)
#   ./prism-install.sh --uninstall "My Instance"   remove it again
#   ./prism-install.sh --list                      show instances
#
# Close Prism first: it rewrites mmc-pack.json on exit and would undo our changes.
# Finds the Flatpak data dir by default; override with PRISM_DATA=/path/to/PrismLauncher.
set -euo pipefail
cd "$(dirname "$0")"

UID_="io.github.hronosin.miracle"
VERSION="$(grep -oE 'VERSION = "[^"]+"' loader/src/io/github/hronosin/miracle/MiracleMain.java | cut -d'"' -f2)"
LIB_NAME="io.github.hronosin:miracle-loader:$VERSION"
LIB_FILE="miracle-loader-$VERSION.jar"

die() { echo "prism-install: $*" >&2; exit 1; }

if [ -z "${PRISM_DATA:-}" ]; then
    for d in "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher" \
             "$HOME/.local/share/PrismLauncher"; do
        if [ -d "$d/instances" ]; then PRISM_DATA="$d"; break; fi
    done
fi
[ -n "${PRISM_DATA:-}" ] && [ -d "$PRISM_DATA/instances" ] \
    || die "Prism data folder not found. Set PRISM_DATA=/path/to/PrismLauncher"

list_instances() {
    echo "Instances in $PRISM_DATA/instances:"
    for i in "$PRISM_DATA/instances"/*/; do
        [ -f "$i/mmc-pack.json" ] && echo "  $(basename "$i")"
    done
}

mode=install
case "${1:-}" in
    --list) list_instances; exit 0 ;;
    --uninstall) mode=uninstall; shift ;;
    "" ) list_instances; echo; die "which instance? usage: $0 [--uninstall] \"Instance Folder Name\"" ;;
esac

INSTANCE="$PRISM_DATA/instances/${1:?instance name}"
PACK="$INSTANCE/mmc-pack.json"
[ -f "$PACK" ] || { list_instances; die "no instance at $INSTANCE"; }

if pgrep -f -i prismlauncher > /dev/null 2>&1; then
    die "Prism is running. Close it first, or it will overwrite mmc-pack.json on exit."
fi

command -v python3 > /dev/null || die "python3 is needed to edit mmc-pack.json"

if [ "$mode" = uninstall ]; then
    python3 - "$PACK" "$UID_" <<'PY'
import json, sys
path, uid = sys.argv[1], sys.argv[2]
with open(path) as f:
    pack = json.load(f)
pack["components"] = [c for c in pack["components"] if c.get("uid") != uid]
with open(path, "w") as f:
    json.dump(pack, f, indent=4)
PY
    rm -f "$INSTANCE/patches/$UID_.json" "$INSTANCE/libraries/"miracle-loader-*.jar
    echo "MiracleLoader removed from $(basename "$INSTANCE"). Mods in the mods folder were left alone."
    exit 0
fi

# --- sanity checks ---------------------------------------------------------------------------
read -r mc_version other_loader < <(python3 - "$PACK" <<'PY'
import json, sys
pack = json.load(open(sys.argv[1]))
uids = {c.get("uid"): c for c in pack.get("components", [])}
mc = uids.get("net.minecraft", {})
ver = mc.get("version") or mc.get("cachedVersion") or "?"
loaders = [u for u in ("net.fabricmc.fabric-loader", "org.quiltmc.quilt-loader",
                       "net.neoforged", "net.minecraftforge", "com.mumfrey.liteloader") if u in uids]
print(ver, loaders[0] if loaders else "-")
PY
)

[ "$other_loader" = "-" ] || die "instance already has $other_loader. Two loaders both want to own mainClass. Use a clean vanilla instance."
case "$mc_version" in
    26.*) ;;
    *) echo "WARNING: instance is Minecraft $mc_version. MiracleLoader targets 26.x (unobfuscated, Java 25)." >&2
       echo "         On older versions class names are obfuscated and mods will find nothing to patch." >&2 ;;
esac

[ -f build/miracle-loader.jar ] && [ -f build/title-mod.jar ] || ./build.sh

# --- install ---------------------------------------------------------------------------------
mkdir -p "$INSTANCE/libraries" "$INSTANCE/patches"
rm -f "$INSTANCE/libraries/"miracle-loader-*.jar
cp build/miracle-loader.jar "$INSTANCE/libraries/$LIB_FILE"

cat > "$INSTANCE/patches/$UID_.json" <<JSON
{
    "formatVersion": 1,
    "uid": "$UID_",
    "name": "MiracleLoader",
    "version": "$VERSION",
    "mainClass": "io.github.hronosin.miracle.MiracleMain",
    "libraries": [
        { "name": "$LIB_NAME", "MMC-hint": "local" }
    ],
    "requires": [ { "uid": "net.minecraft" } ],
    "order": 10
}
JSON

cp "$PACK" "$PACK.bak"
python3 - "$PACK" "$UID_" "$VERSION" <<'PY'
import json, sys
path, uid, version = sys.argv[1:4]
with open(path) as f:
    pack = json.load(f)
comps = [c for c in pack["components"] if c.get("uid") != uid]
comps.append({"uid": uid, "cachedName": "MiracleLoader", "cachedVersion": version,
              "cachedRequires": [{"uid": "net.minecraft"}]})
pack["components"] = comps
with open(path, "w") as f:
    json.dump(pack, f, indent=4)
PY

# Prism uses either "minecraft" or ".minecraft" as the game dir.
GAME_DIR="$INSTANCE/minecraft"
[ -d "$INSTANCE/.minecraft" ] && GAME_DIR="$INSTANCE/.minecraft"
mkdir -p "$GAME_DIR/mods"
cp build/title-mod.jar "$GAME_DIR/mods/"
extra=""
for m in dirt-diamonds super-jump sprint-jump; do
    if [ -f "build/$m.jar" ]; then
        cp "build/$m.jar" "$GAME_DIR/mods/"
        extra="$extra + $m.jar"
    fi
done

cat <<EOF
MiracleLoader $VERSION installed into "$(basename "$INSTANCE")" (Minecraft $mc_version).
  loader : $INSTANCE/libraries/$LIB_FILE
  patch  : $INSTANCE/patches/$UID_.json
  mods   : $GAME_DIR/mods   (title-mod.jar$extra copied)
  backup : $PACK.bak

Now open Prism, launch the instance and watch the log for [Miracle] and [title-mod] lines.
EOF
