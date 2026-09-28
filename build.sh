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

javac_() { "$JAVAC" --release 25 -encoding UTF-8 -Xlint:all,-serial -Werror "$@"; }

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
    "$JAR" --create --file "$jar" -C "$classes" .
}

echo "==> loader"
build_jar loader "$OUT/miracle-loader.jar"

echo "==> fake game"
build_jar examples/fake-game "$OUT/fake-minecraft.jar"

API="$OUT/miracle-loader.jar:$OUT/fake-minecraft.jar"

echo "==> example mods"
build_jar examples/hello-mod "$OUT/mods/hello-mod.jar" "$API"
build_jar examples/chaos-mod "$OUT/mods/chaos-mod.jar" "$API"
# Real-game mod: needs only the loader on the class path, no Minecraft jar.
build_jar examples/title-mod "$OUT/title-mod.jar" "$OUT/miracle-loader.jar"

echo "==> test mods"
for m in tests/*-mod; do
    build_jar "$m" "$OUT/test-mods/$(basename "$m").jar" "$API"
done

echo "Built. Try: ./run.sh"
