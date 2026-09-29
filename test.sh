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
        -cp "$ROOT/build/miracle-loader.jar:${GAME_JAR:-$ROOT/build/fake-minecraft.jar}" \
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
expect_not ghost "but this game has no such class"                   # the class exists, only the method doesn't
expect ghost "[FakeMinecraft] done"

# --- target class that doesn't exist -> loud warning, game still runs ------------------------
run_with typo "$T/typo-mod.jar"
expect_code typo 0
expect typo "RGCT: typo-mod hook(s) net.minecraft.world.entity.player.Playre, but this game has no such class. Wrong game version, or a typo in the class name?"

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

# --- OSHI: bring-your-own bytecode tools ----------------------------------------------------
run_with oshi "$T/oshi-mod.jar" "$T/clash-hi.jar"
expect_code oshi 0
expect oshi "<- oshi-mod (OSHI: brought its own tools)"
expect oshi "OSHI: mod 'oshi-mod' brings its own hooks for net.minecraft.world.entity.player.Player"
expect oshi "motd=HI"                                               # layers run first, then rawBytes...
run_with oshi2 "$T/oshi-mod.jar"
expect oshi2 "motd=old school"                                      # ...which rewrote the vanilla constant

# --- OSHI: baked variants picked by game version ---------------------------------------------
run_with variant-none "$T/variant-mod.jar"
expect_code variant-none 0
expect variant-none "Game: Minecraft unknown"
expect variant-none "[variant-mod] running the plain classes"

JAVA_OPTS=-Dmiracle.gameVersion=fake-1 run_with variant "$T/variant-mod.jar"
expect_code variant 0
expect variant "Game: Minecraft fake-1"
expect variant "OSHI: variant-mod uses its variant baked for fake-1"
expect variant "[variant-mod] running the variant baked for fake-1"
expect_not variant "running the plain classes"

JAVA_OPTS="-Dmiracle.gameVersion=fake-2 -Dmiracle.obfuscated=true" run_with variant-missing "$T/variant-mod.jar" "$M/hello-mod.jar"
expect_code variant-missing 1
expect variant-missing "Game: Minecraft fake-2 (obfuscated)"
expect variant-missing "Mod variant-mod (variant-mod 0.0.0) has no variant for Minecraft fake-2 (obfuscated). It was baked for [fake-1]"
expect variant-missing "Mod hello-mod was never baked"

JAVA_OPTS=-Dmiracle.gameVersion=fake-3 run_with variant-unchecked "$T/variant-mod.jar"
expect_code variant-unchecked 0
expect variant-unchecked "Mod variant-mod was checked on [], not on fake-3"
expect variant-unchecked "[variant-mod] running the plain classes"

# --- OSHI: bake once against the dictionaries, run on the obfuscated game --------------------
BAKED="$ROOT/build/test-baked"
rm -rf "$BAKED" && mkdir -p "$BAKED"
cp "$M/hello-mod.jar" "$T/intercept-mod.jar" "$T/stack-a.jar" "$T/stack-b.jar" \
   "$T/clash-a.jar" "$T/clash-hi.jar" "$T/fly-mod.jar" "$T/wings-mod.jar" "$BAKED/"
out="$("$JAVA" -jar build/miracle-bake.jar --native fake=build/fake-minecraft.jar \
        --obf fake-obf=build/fake-minecraft-obf.jar,test-fixtures/fake-obf-game/mappings.txt "$BAKED"/*.jar 2>&1)"
expect bake "[bake] intercept-mod.jar"
expect bake "MISSING 1:"
expect bake "RGCT target net.minecraft.world.entity.player.Player#fly"
expect bake "not baked. Drop this version, or add a fallback for what's missing (fallback/fake-obf/src in the mod's sources)."
[ "$(grep -c "game references translated.*, baked" <<< "$out")" -eq 7 ] && pass=$((pass + 1)) \
    || { fail=$((fail + 1)); echo "FAIL [bake]: expected 7 baked mods"; echo "$out"; }
expect bake "[bake] wings-mod.jar (1 classes, fallbacks for [fake-obf])"
expect bake "5 game references translated, 1 fallback method(s) + 1 added, baked"
expect bake "note: added test.wings.WingsMod#label(Lnet/minecraft/world/entity/player/Player;)Ljava/lang/String;"

# --- OSHI fallbacks: the hole is filled only where the fallback applies -----------------------
GAME_JAR="$ROOT/build/fake-minecraft-obf.jar" run_with obf-wings "$BAKED/wings-mod.jar"
expect_code obf-wings 0
expect obf-wings "OSHI: wings-mod uses its variant baked for fake-obf"
expect obf-wings "[wings-mod] main onLaunch"                                # untouched method kept
expect obf-wings "[wings-mod] no wings in this version, Steve jumps instead #1" # fallback + real flap() + real field
expect obf-wings "jumpPower=0.84"                                          # serializable lambda from the fallback
expect obf-wings "<- wings-mod  [modifies return]"                         # EffectScan found the renamed lambda
expect_not obf-wings "flap #"

run_with wings-native "$BAKED/wings-mod.jar"                               # readable game: no variant, main code
expect_code wings-native 0
expect wings-native "fly                          @HEAD             <- wings-mod"
expect_not wings-native "uses its variant"
expect wings-native "jumpPower=0.42"

OBF="$ROOT/build/fake-minecraft-obf.jar"
GAME_JAR="$OBF" run_with obf-vanilla
expect obf-vanilla "Game: Minecraft fake-obf (obfuscated)"
expect obf-vanilla "jumpPower=0.42"

GAME_JAR="$OBF" run_with obf-intercept "$BAKED/intercept-mod.jar"
expect_code obf-intercept 0
expect obf-intercept "OSHI: intercept-mod uses its variant baked for fake-obf"
expect obf-intercept "jumpPower=0.84"
expect obf-intercept "Steve takes 5.0 damage from reduced zombie"          # a(F,String), not a() or a(J,D,Z,C)
expect obf-intercept "[intercept-mod] explosion cancelled"
expect_not obf-intercept "[FakeMinecraft] BOOM"
expect obf-intercept "move 20 0.5 true x -> 1020.5"
expect obf-intercept "moved=1020.75"
expect obf-intercept "motd=miracle, self=null"
expect obf-intercept "nobody score=1337"                                   # ((Player) self) became ((o.a) self)
expect obf-intercept "ticks=101"

GAME_JAR="$OBF" run_with obf-stack "$BAKED/intercept-mod.jar" "$BAKED/stack-a.jar" "$BAKED/stack-b.jar"
expect_code obf-stack 0
expect obf-stack "jumpPower=1.56"
expect obf-stack "[stack-b] sees jump power 0.42"                          # method ref + helper, baked
expect obf-stack "ticks=50"
expect obf-stack "o.a  (net.minecraft.world.entity.player.Player)"
expect obf-stack "e()F (getJumpPower)"

GAME_JAR="$OBF" run_with obf-hello "$BAKED/hello-mod.jar"
expect_code obf-hello 0
expect obf-hello "[hello-mod] hop, bytecode patch for Steve"
expect obf-hello "[hello-mod] constructor done, self is a Player: true"   # instanceof Player -> o.a
expect obf-hello "[hello-mod] getScore returning for 'Steve'"

GAME_JAR="$OBF" run_with obf-prio "$BAKED/clash-a.jar" "$BAKED/clash-hi.jar"
expect_code obf-prio 0
expect obf-prio "motd=HI"

GAME_JAR="$OBF" run_with obf-fly "$BAKED/fly-mod.jar"
expect_code obf-fly 1
expect obf-fly "Mod fly-mod (fly-mod 0.0.0) has no variant for Minecraft fake-obf (obfuscated). It was baked for [] and checked on [fake]"

GAME_JAR="$OBF" run_with obf-unbaked "$M/hello-mod.jar"
expect obf-unbaked "Mod hello-mod was never baked, and Minecraft fake-obf is obfuscated."
expect obf-unbaked "RGCT: hello-mod hook(s) net.minecraft.world.entity.player.Player, but this game has no such class. Minecraft fake-obf is obfuscated: the mod needs a variant baked for it"

# --- MiracleToolChain command line (offline parts) --------------------------------------------
CLI_HOME="$ROOT/build/test-cli"
rm -rf "$CLI_HOME" && mkdir -p "$CLI_HOME"
cli() { out="$(cd "$CLI_HOME" && MIRACLE_HOME="$CLI_HOME/cache" "$JAVA" -jar "$ROOT/build/miracle.jar" "$@" 2>&1)"; code=$?; }

cli help
expect_code cli-help 0
expect cli-help "Forge hammers, Fabric stitches, we just pray."
expect cli-help "miracle genesis <name>          (new)"

cli genesis holy-hops --minecraft 26.2 --package org.example.hops
expect_code genesis 0
expect genesis "In the beginning there was nothing. Then there was Holy Hops."
for f in miracle.mod.toml miracle.project.toml src/org/example/hops/HolyHops.java .gitignore fallback/README.md; do
    if [ -f "$CLI_HOME/holy-hops/$f" ]; then pass=$((pass + 1)); else fail=$((fail + 1)); echo "FAIL [genesis]: no $f"; fi
done
grep -q 'entrypoint = "org.example.hops.HolyHops"' "$CLI_HOME/holy-hops/miracle.mod.toml" && pass=$((pass + 1)) \
    || { fail=$((fail + 1)); echo "FAIL [genesis]: wrong entrypoint"; }
grep -q 'minecraft = "26.2"' "$CLI_HOME/holy-hops/miracle.project.toml" && pass=$((pass + 1)) \
    || { fail=$((fail + 1)); echo "FAIL [genesis]: wrong minecraft"; }

cli genesis holy-hops --minecraft 26.2
expect_code genesis-again 1
expect genesis-again "HERESY:"
expect genesis-again "already exists and isn't empty. Creation happens ex nihilo."

cli bake
expect_code bake-nowhere 1
expect bake-nowhere "HERESY: No miracle.project.toml here or in any parent folder. Start one with: miracle genesis my-mod"

cli smite
expect_code unknown 1
expect unknown "HERESY: 'smite' is not in the scripture."

(cd "$CLI_HOME/holy-hops" && MIRACLE_HOME="$CLI_HOME/cache" "$JAVA" -jar "$ROOT/build/miracle.jar" pray > "$CLI_HOME/pray.log" 2>&1); code=$?; out="$(cat "$CLI_HOME/pray.log")"
expect_code pray-what 1
expect pray-what "pray for what? miracle pray client, or miracle pray server"

# --- rituals and the useful ones -------------------------------------------------------------
export MIRACLE_IMPATIENT=1
pcli() { out="$(cd "$CLI_HOME/holy-hops" && MIRACLE_HOME="$CLI_HOME/cache" "$JAVA" -jar "$ROOT/build/miracle.jar" "$@" 2>&1)"; code=$?; }

cli gradle
expect_code gradle 0
expect gradle "Starting a Gradle Daemon (subsequent builds will be faster)"
expect gradle "BUILD SUCCESSFUL in 5m 3s"
expect gradle "...just kidding. There is no Gradle here, and nothing was built."

cli forge
expect_code forge 1
expect forge "'forge' is not a miracle command. Did you mean:"

cli fast --seconds 1
expect_code fast 0
expect fast "The fast is over. You have built nothing, and you are better for it."

cli tithe
expect tithe "has been offered."
expect tithe "Nothing was deleted."

pcli heresy
expect_code heresy-clean 0
expect heresy-clean "No heresy found"
mkdir -p "$CLI_HOME/holy-hops/src/org/example/hops"
printf 'package org.example.hops;\nimport net.minecraftforge.fml.common.Mod;\nimport org.spongepowered.asm.mixin.Mixin;\n// TODO: repent\n// FIXME the mixins /* */\nclass Sinner {}\n' \
    > "$CLI_HOME/holy-hops/src/org/example/hops/Sinner.java"
pcli heresy
expect_code heresy 1
expect heresy "The Inquisition has found 2 heresies:"
expect heresy "Forge. The hammer has fallen"
expect heresy "Mixin. Cringe, as foretold"
expect heresy "Penance: 2 Hail Maries, and a rewrite with RGCT."

pcli messages
expect messages "2 message(s) left by past Tarnished:"
expect messages "Try repent"
expect messages "Be wary of the mixins"
expect_not messages "/*"
rm "$CLI_HOME/holy-hops/src/org/example/hops/Sinner.java"

pcli bonfire
expect_code bonfire-nothing 1
expect bonfire-nothing "No worlds yet"
mkdir -p "$CLI_HOME/holy-hops/run/server-26.2/world/region" "$CLI_HOME/holy-hops/run/server-26.2/logs"
echo "old" > "$CLI_HOME/holy-hops/run/server-26.2/world/level.dat"
echo "log" > "$CLI_HOME/holy-hops/run/server-26.2/logs/latest.log"
pcli bonfire
expect_code bonfire 0
expect bonfire "BONFIRE LIT"
echo "wrecked" > "$CLI_HOME/holy-hops/run/server-26.2/world/level.dat"
pcli grace rest
expect_code grace-rest 0
expect grace-rest "You rested at the site of grace."
grep -qx old "$CLI_HOME/holy-hops/run/server-26.2/world/level.dat" && pass=$((pass + 1)) \
    || { fail=$((fail + 1)); echo "FAIL [grace-rest]: world not restored"; }
pcli bonfire list
expect bonfire-list "_before-rest"

pcli exorcise
expect_code exorcise-dry 0
expect exorcise-dry "run/server-26.2/logs"
expect exorcise-dry "Say the words to cast them out: miracle exorcise --yes"
pcli exorcise --yes
expect exorcise "The power of Miracle compels you!"
[ ! -e "$CLI_HOME/holy-hops/run/server-26.2/logs" ] && [ -e "$CLI_HOME/holy-hops/run/server-26.2/world/level.dat" ] \
    && [ -d "$CLI_HOME/holy-hops/run/server-26.2/bonfires" ] && pass=$((pass + 1)) \
    || { fail=$((fail + 1)); echo "FAIL [exorcise]: took the wrong things"; }

cli zandatsu "$ROOT/build/test-mods/needs-lib.jar"
expect_code zandatsu 0
expect zandatsu "BLADE MODE. Cutting needs-lib.jar"
expect zandatsu "Spine:      org.test.needslib.NeedsLib"
expect zandatsu "ZANDATSU! 1 class(es) taken. Rules of Nature."
cli zandatsu "$ROOT/build/test-mods/patron-lib.jar"
expect zandatsu-patches "Patches:    Player"

cli genesis --templates
expect templates "grace       Elden Ring"
cli genesis stylish-mod --template stylish --minecraft 26.2
expect_code template 0
grep -q 'depends = \["miracle-toolchain>=0.2.0"\]' "$CLI_HOME/stylish-mod/miracle.mod.toml" \
    && grep -q "Smokin' Sexy Style" "$CLI_HOME/stylish-mod/src/com/example/stylishmod/StylishMod.java" \
    && ! grep -q "__" "$CLI_HOME/stylish-mod/src/com/example/stylishmod/StylishMod.java" && pass=$((pass + 1)) \
    || { fail=$((fail + 1)); echo "FAIL [template]: bad stylish project"; }
cli genesis nope --template bloodborne
expect_code template-bad 1
expect template-bad "No template called 'bloodborne'."
unset MIRACLE_IMPATIENT

cli confess
expect confess "Forgive me, Father, for I have built mods."
expect confess "not inside a mod project"
expect confess "Aura: "

# --- depends: libraries, order, versions, patrons ----------------------------------------------
run_with depends "$T/needs-lib.jar" "$T/dep-lib.jar" "$T/patron-lib.jar" "$T/victim-mod.jar"
expect_code depends 0
expect depends "Dependency Library (dep-lib 1.2.0), a library"
expect depends "[needs-lib] The Lord is my shepherd; I shall not want for Forge."
expect depends "[needs-lib] order: [dep-lib, patron-lib, a-needs-lib, patron-lib-victim]"
expect depends "[needs-lib] owner: a-needs-lib, psalm owner: dep-lib, String owner: none"
expect depends "[needs-lib] dep-lib is a library: true, launched: true, client: true"
expect depends "[patron] launched before transform: false"
expect depends "jumpFromGround"
expect depends "<- a-needs-lib"
expect depends "[patron] jump, on behalf of a-needs-lib"
expect depends "[patron] refused: 'patron-lib' may only patch on behalf of mods that depend on it, and 'patron-lib-victim' doesn't"

run_with too-old "$T/needs-new-lib.jar" "$T/dep-lib.jar"
expect_code too-old 1
expect too-old "Some mods came without what they need:"
expect too-old "needs-new-lib needs dep-lib >= 2.0, but dep-lib 1.2.0 is here. Update it."
expect too-old "needs-new-lib needs miracle >= 99, but miracle 0.2.0 is here. Update it."

run_with ghost-dep "$T/needs-ghost.jar"
expect_code ghost-dep 1
expect ghost-dep "needs-ghost needs holy-grail, which is not in the mods folder."
expect ghost-dep "needs-ghost needs miracle-toolchain, which is not in the mods folder. It's the MiracleToolChain library"

run_with cycle "$T/cycle-a.jar" "$T/cycle-b.jar"
expect_code cycle 1
expect cycle "These mods depend on each other in a circle: cycle-a -> cycle-b -> cycle-a."

echo
echo "passed: $pass, failed: $fail"
[ "$fail" -eq 0 ]
