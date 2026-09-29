#!/usr/bin/env bash
# MiracleLoader build. No Gradle, no Maven, no Loom — javac and jar, as promised.
set -euo pipefail
cd "$(dirname "$0")"

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"

ver="$("$JAVAC" -version 2>&1 | grep '^javac' | grep -oE '[0-9]+' | head -1)"
if [ "${ver:-0}" -lt 25 ]; then
    echo "Need JDK 25+ (the ClassFile API and Minecraft 26.x both want it), found: $("$JAVAC" -version 2>&1)" >&2
    echo "Fedora: sudo dnf install java-25-openjdk-devel" >&2
    exit 1
fi

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/mods" "$OUT/test-mods"

# Lint everything in our code, but not the environment's quirks:
#   -path:      a stale jar manifest somewhere on the class path
#   -classfile: annotations inside Minecraft's jar whose classes aren't shipped (JetBrains @Contract)
javac_() { "$JAVAC" --release 25 -encoding UTF-8 -Xlint:all,-serial,-path,-classfile -Werror "$@"; }

# $1 = source root, $2 = output jar, $3 = extra class path (optional)
build_jar() {
    local src="$1" jar="$2" cp="${3:-}" name
    name="$(basename "$jar" .jar)"
    local classes="$OUT/classes/$name"
    mkdir -p "$classes"
    # shellcheck disable=SC2046
    javac_ ${cp:+-cp "$cp"} -d "$classes" $(find "$src/src" -name '*.java')
    if [ -f "$src/miracle.mod.toml" ]; then
        cp "$src/miracle.mod.toml" "$classes/"
    fi
    if [ -d "$src/resources" ]; then
        cp -r "$src/resources/." "$classes/"
    fi
    # Hand-made variants (tests): baked/<version>/src, as miracle-bake would lay them out.
    if [ -d "$src/baked" ]; then
        local versions=()
        for v in "$src"/baked/*/; do
            v="$(basename "$v")"
            versions+=("\"$v\"")
            # shellcheck disable=SC2046
            javac_ ${cp:+-cp "$cp"} -d "$classes/META-INF/miracle/baked/$v" $(find "$src/baked/$v/src" -name '*.java')
        done
        mkdir -p "$classes/META-INF/miracle"
        printf 'baked = [%s]\nchecked = []\n' "$(IFS=,; echo "${versions[*]}")" > "$classes/META-INF/miracle/bake.toml"
    fi
    "$JAR" --create --file "$jar" -C "$classes" .
}

echo "==> loader"
build_jar loader "$OUT/miracle-loader.jar"

echo "==> miracle-bake"
build_jar tools/bake "$OUT/miracle-bake.jar"
printf 'Main-Class: io.github.hronosin.miracle.bake.Bake\n' > "$OUT/classes/bake-manifest.txt"
"$JAR" --update --file "$OUT/miracle-bake.jar" --manifest "$OUT/classes/bake-manifest.txt"

echo "==> fake game"
build_jar examples/fake-game "$OUT/fake-minecraft.jar"

echo "==> fake obfuscated game (for OSHI tests)"
build_jar test-fixtures/fake-obf-game "$OUT/fake-minecraft-obf.jar"

API="$OUT/miracle-loader.jar:$OUT/fake-minecraft.jar"

echo "==> example mods"
build_jar examples/hello-mod "$OUT/mods/hello-mod.jar" "$API"
build_jar examples/chaos-mod "$OUT/mods/chaos-mod.jar" "$API"
# Real-game mod: needs only the loader on the class path, no Minecraft jar.
build_jar examples/title-mod "$OUT/title-mod.jar" "$OUT/miracle-loader.jar"

echo "==> real-game mods (compiled against Minecraft itself)"
# Needs the client jar AND the libraries that version uses (Minecraft's classes extend
# Brigadier, DataFixerUpper, ...). Found automatically from Prism Launcher; otherwise set
#   MC_JAR=/path/to/client.jar MC_LIBS=/folder/with/exactly/that/versions/jars
PRISM_ROOT=""
for d in "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher" \
         "$HOME/.local/share/PrismLauncher"; do
    if [ -d "$d/libraries/com/mojang/minecraft" ]; then PRISM_ROOT="$d"; break; fi
done
if [ -z "${MC_JAR:-}" ] && [ -n "$PRISM_ROOT" ]; then
    MC_JAR="$(ls -1 "$PRISM_ROOT"/libraries/com/mojang/minecraft/26.*/minecraft-26.*-client.jar 2>/dev/null | sort -V | tail -1 || true)"
fi

MC_CP=""
if [ -n "${MC_JAR:-}" ] && [ -f "$MC_JAR" ]; then
    if [ -n "${MC_LIBS:-}" ]; then
        MC_CP="$(find "$MC_LIBS" -name '*.jar' | sort | tr '\n' ':')"
    elif [ -n "$PRISM_ROOT" ]; then
        # Only the libraries this Minecraft version declares, per Prism's metadata. Globbing the
        # whole libraries folder drags in every other instance's jars (old Forge and friends).
        mc_version="$(basename "$(dirname "$MC_JAR")")"
        MC_CP="$(python3 tools/mc-classpath.py "$PRISM_ROOT" "$mc_version" || true)"
        [ -n "$MC_CP" ] || echo "    no Prism metadata for $mc_version (launch that instance once)"
    fi
fi
if [ -n "$MC_CP" ]; then
    echo "    against $MC_JAR ($(tr ':' '\n' <<< "$MC_CP" | grep -c . ) libraries)"
    MC_CP="$MC_JAR:$MC_CP"
    build_jar examples/dirt-diamonds "$OUT/dirt-diamonds.jar" "$OUT/miracle-loader.jar:$MC_CP"
    build_jar examples/super-jump "$OUT/super-jump.jar" "$OUT/miracle-loader.jar:$MC_CP"
    build_jar examples/sprint-jump "$OUT/sprint-jump.jar" "$OUT/miracle-loader.jar:$MC_CP"

    # OSHI: check the mods against every dictionary you've fetched (tools/fetch-dictionary.sh),
    # and bake a variant for each obfuscated one.
    DICTS="${MIRACLE_DICTIONARIES:-$HOME/.cache/miracle/dictionaries}"
    mc_id="$(python3 -c "import json,sys,zipfile; print(json.loads(zipfile.ZipFile(sys.argv[1]).read('version.json'))['id'])" "$MC_JAR")"
    bake_args=(--native "$mc_id=$MC_JAR")
    for d in "$DICTS"/*/; do
        [ -f "$d/client.jar" ] || continue
        v="$(basename "$d")"
        if [ -f "$d/mappings.txt" ]; then
            bake_args+=(--obf "$v=$d/client.jar,$d/mappings.txt")
        elif [ "$v" != "$mc_id" ]; then
            bake_args+=(--native "$v=$d/client.jar")
        fi
    done
    if [ "${#bake_args[@]}" -le 2 ]; then
        echo "    not baked: no dictionaries in $DICTS. These mods run on unobfuscated versions (26.x) only."
        echo "    For 1.21.x: tools/fetch-dictionary.sh 1.21.11, then build again."
    else
        echo "==> baking (OSHI)"
        "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$OUT/miracle-bake.jar" "${bake_args[@]}" \
            "$OUT/dirt-diamonds.jar" "$OUT/super-jump.jar" "$OUT/sprint-jump.jar" "$OUT/title-mod.jar"
    fi
else
    echo "    skipped: no Minecraft 26.x jar + libraries found (launch a 26.x instance in Prism once, or set MC_JAR=... MC_LIBS=...)"
fi

echo "==> test mods"
for m in tests/*/; do
    m="${m%/}"
    build_jar "$m" "$OUT/test-mods/$(basename "$m").jar" "$API"
done

echo "Built. Try: ./run.sh"
