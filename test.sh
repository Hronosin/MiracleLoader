#!/usr/bin/env bash
# Smoke tests: run the fake game with different mod sets and check what comes out.
set -uo pipefail
cd "$(dirname "$0")"
export LC_ALL=C.UTF-8  # the crash banner is in Russian; keep the JVM from printing ????
./build.sh > /dev/null || { echo "build failed"; exit 1; }

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
ROOT="$(pwd)"
pass=0
fail=0

# run_with <name> <mod jars...>  -> sets $out and $code
run_with() {
    local name="$1"; shift
    local dir="$ROOT/build/test-runs/$name"
    rm -rf "$dir" && mkdir -p "$dir/mods"
    for j in "$@"; do cp "$j" "$dir/mods/"; done
    out="$(cd "$dir" && "$JAVA" -Dmiracle.dump=dump \
        -cp "$ROOT/build/miracle-loader.jar:$ROOT/build/fake-minecraft.jar" \
        io.github.hronosin.miracle.MiracleMain --username Steve 2>&1)"
    code=$?
}

expect() {
    local what="$1" needle="$2"
    if grep -qF -- "$needle" <<< "$out"; then
        pass=$((pass + 1))
    else
        fail=$((fail + 1))
        echo "FAIL [$what]: expected to see: $needle"
        echo "----- output -----"; echo "$out"; echo "------------------"
    fi
}

expect_not() {
    local what="$1" needle="$2"
    if grep -qF -- "$needle" <<< "$out"; then
        fail=$((fail + 1))
        echo "FAIL [$what]: did not expect: $needle"
    else
        pass=$((pass + 1))
    fi
}

expect_code() {
    local what="$1" want="$2"
    if [ "$code" -eq "$want" ]; then pass=$((pass + 1)); else
        fail=$((fail + 1)); echo "FAIL [$what]: exit code $code, wanted $want"; echo "$out"
    fi
}

M=build/mods
T=build/test-mods

# --- vanilla: no mods ------------------------------------------------------------------------
run_with vanilla
expect_code vanilla 0
expect vanilla "Found 0 mod(s)."
expect vanilla "Vanilla, but with extra steps."
expect vanilla "Steve jumps"
expect vanilla "[FakeMinecraft] done"
expect_not vanilla "[hello-mod]"

# --- hello-mod: head / return / ctor / static ------------------------------------------------
run_with hello "$M/hello-mod.jar"
expect_code hello 0
expect hello "[hello-mod] launched. Miracles are real."
expect hello "[hello-mod] hop, bytecode patch for Steve"
expect hello "[hello-mod] constructor head, self=null"
expect hello "[hello-mod] constructor done, self is a Player: true"
expect hello "[hello-mod] getScore returning for 'Steve'"
expect hello "[hello-mod] getScore returning for ''"
expect hello "[hello-mod] static method, self=null"
expect hello "[FakeMinecraft] score=42"
expect hello "[FakeMinecraft] nobody score=0"
expect hello "jumpFromGround"
expect hello "[FakeMinecraft] done"
if [ -f "build/test-runs/hello/dump/net/minecraft/world/entity/player/Player.class" ]; then
    pass=$((pass + 1)); else fail=$((fail + 1)); echo "FAIL [hello]: patched class was not dumped"; fi

# --- raw transform + hooks on the same class -------------------------------------------------
run_with chaos "$M/hello-mod.jar" "$M/chaos-mod.jar"
expect_code chaos 0
expect chaos "Steve levitates"
expect_not chaos "Steve jumps"
expect chaos "[hello-mod] hop, bytecode patch for Steve"
expect chaos "mod 'chaos-mod' raw-patches net.minecraft.world.entity.player.Player"

# --- target that doesn't exist -> warning, game still runs -----------------------------------
run_with ghost "$T/ghost-mod.jar"
expect_code ghost 0
expect ghost "no such method with a body exists. Wrong game version?"
expect ghost "[FakeMinecraft] done"

# --- mod throws in transform() -> crash banner names it --------------------------------------
run_with boom "$T/boom-mod.jar"
expect_code boom 1
expect boom "ЧУДА НЕ ПРОИЗОШЛО"
expect boom "Mod Boom Mod (boom-mod 0.0.0) failed in transform()"
expect boom "kaboom"
expect_not boom "[FakeMinecraft]"

# --- mod touches game classes during transform() -> refuse to start -------------------------
run_with eager "$T/eager-mod.jar"
expect_code eager 1
expect eager "net.minecraft.world.entity.player.Player (targeted by eager-mod)"
expect_not eager "[FakeMinecraft] starting"

# --- two mods with the same id ---------------------------------------------------------------
cp "$M/hello-mod.jar" build/hello-copy.jar
run_with dupe "$M/hello-mod.jar" build/hello-copy.jar
expect_code dupe 1
expect dupe "Two mods claim the id 'hello-mod'"

echo
echo "passed: $pass, failed: $fail"
[ "$fail" -eq 0 ]
