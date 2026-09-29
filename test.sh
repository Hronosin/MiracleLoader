#!/usr/bin/env bash
# Smoke tests: run the fake game with different mod sets and check what comes out.
set -uo pipefail
cd "$(dirname "$0")"
export LC_ALL=C.UTF-8  # keep the JVM from turning non-ASCII output into ????
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
    # shellcheck disable=SC2086
    out="$(cd "$dir" && "$JAVA" ${JAVA_OPTS:-} -Dmiracle.dump=dump \
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
expect boom "NO MIRACLE OCCURRED"
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

# --- vanilla values of the methods intercept-mod changes -------------------------------------
run_with vanilla2
expect vanilla2 "jumpPower=0.42"
expect vanilla2 "Steve takes 10.0 damage from zombie"
expect vanilla2 "[FakeMinecraft] BOOM"
expect vanilla2 "moved=2.5"
expect vanilla2 "motd=vanilla"

# --- interceptHead / interceptReturn ---------------------------------------------------------
run_with intercept "$T/intercept-mod.jar"
expect_code intercept 0
expect intercept "jumpPower=0.84"                                   # float return replaced
expect intercept "Steve takes 5.0 damage from reduced zombie"       # float + String args replaced
expect intercept "[intercept-mod] explosion cancelled"
expect_not intercept "[FakeMinecraft] BOOM"                         # void method cancelled
expect intercept "move 20 0.5 true x -> 1020.5"                     # long/boolean args, double & char untouched
expect intercept "[intercept-mod] move args at return: 20 x"        # args visible at return
expect intercept "moved=1020.75"                                    # double return replaced
expect intercept "motd=miracle, self=null"                          # static, reference return
expect intercept "[FakeMinecraft] score=42"
expect intercept "nobody score=1337"                                # cancel with an int value
expect intercept "ticks=101"                                        # static int return
expect intercept "uses interceptHead on a constructor"
expect intercept "intercept@RETURN"
expect intercept "[FakeMinecraft] done"

# --- observe + intercept on the same methods -------------------------------------------------
run_with both "$M/hello-mod.jar" "$T/intercept-mod.jar"
expect_code both 0
expect both "[hello-mod] hop, bytecode patch for Steve"
expect both "[hello-mod] getScore returning for 'Steve'"
expect_not both "[hello-mod] getScore returning for ''"             # cancelled: return hooks skipped too
expect both "nobody score=1337"
expect both "[hello-mod] static method, self=null"
expect both "ticks=101"

# --- wrong value type -> clear error naming the mod ------------------------------------------
run_with badtype "$T/badtype-mod.jar"
expect_code badtype 1
expect badtype "NO MIRACLE OCCURRED"
expect badtype "return value must be a Float (primitive float), got java.lang.Double 2.0"
expect badtype "thrown by a hook of mod 'badtype-mod'"

# --- layers: effects from several mods stack, whatever the load order ------------------------
run_with stack "$T/stack-a.jar" "$T/stack-b.jar"
expect_code stack 0
expect stack "jumpPower=0.78"                                       # (0.42 + 0.1) * 1.5
expect stack "[stack-b] sees jump power 0.42"
expect stack "ticks=1"                                              # clamp [0,50] leaves 1 alone
expect stack "<- stack-a  [modifies return]"
expect stack "<- stack-b  [modifies return]"                         # found through a helper method
expect stack "<- stack-a  [cancels]"
expect_not stack "may all"

run_with stack3 "$T/intercept-mod.jar" "$T/stack-a.jar" "$T/stack-b.jar"
expect_code stack3 0
expect stack3 "jumpPower=1.56"                                      # (0.42 + 0.1) * 2 * 1.5
expect stack3 "[stack-b] sees jump power 0.42"                      # snapshot, not intercept-mod's 0.84
expect stack3 "ticks=50"                                            # 1 + 100, clamped to 50
expect stack3 "Steve takes 5.0 damage from reduced zombie"
expect_not stack3 "[FakeMinecraft] BOOM"                            # both cancel: still cancelled

# --- layers: conflicts -----------------------------------------------------------------------
run_with clash "$T/clash-a.jar" "$T/clash-b.jar"
expect_code clash 1
expect clash "mods 'clash-a', 'clash-b' may all set its return value (net.minecraft.world.entity.player.Player#motd) at priority 0"
expect clash "NO MIRACLE OCCURRED"
expect clash "RgctConflictException: RGCT conflict at net.minecraft.world.entity.player.Player#motd()Ljava/lang/String;, return value (priority 0): 'clash-a' sets \"A\", 'clash-b' sets \"B\""
expect_not clash "motd="

JAVA_OPTS=-Dclash.b=A run_with agree "$T/clash-a.jar" "$T/clash-b.jar"
expect_code agree 0
expect agree "motd=A"                                               # same value: no conflict

run_with prio "$T/clash-a.jar" "$T/clash-b.jar" "$T/clash-hi.jar"
expect_code prio 0
expect prio "motd=HI"                                               # priority 5 beats the tie at 0
expect prio "<- clash-hi  [sets return, priority 5]"

echo
echo "passed: $pass, failed: $fail"
[ "$fail" -eq 0 ]
