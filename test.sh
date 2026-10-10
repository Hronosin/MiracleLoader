#!/usr/bin/env bash
# Smoke tests: run the fake game with different mod sets and check what comes out.
set -uo pipefail
cd "$(dirname "$0")"
export LC_ALL=C.UTF-8  # keep the JVM from turning non-ASCII output into ????
./build.sh > /dev/null || { echo "build failed"; exit 1; }

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
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
    if [ -n "${AGENT:-}" ]; then
        # The same game, started as it is, with MiracleLoader as a Java agent.
        out="$(cd "$dir" && "$JAVA" ${JAVA_OPTS:-} -Dmiracle.dump=dump -javaagent:"$ROOT/build/miracle-loader.jar" \
            -cp "${GAME_JAR:-$ROOT/build/fake-minecraft.jar}" net.minecraft.client.main.Main --username Steve 2>&1)"
    else
        out="$(cd "$dir" && "$JAVA" ${JAVA_OPTS:-} -Dmiracle.dump=dump \
            -cp "$ROOT/build/miracle-loader.jar:${GAME_JAR:-$ROOT/build/fake-minecraft.jar}" \
            io.github.hronosin.miracle.MiracleMain --username Steve 2>&1)"
    fi
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
expect vanilla "Cool :D"                                              # the last word of a good ending
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
expect_not boom "Cool :D"                                             # not after a crash
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
expect vanilla2 "badge=evetS"

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
expect_not intercept "on a constructor"                               # supported since 1.6
expect intercept "intercept@RETURN"
expect intercept "[FakeMinecraft] done"

# --- interceptHead on a constructor: arguments change before super(), no self, no cancel ------
run_with ctor "$T/ctor-mod.jar"
expect_code ctor 0
expect ctor "[ctor-mod] making 'Steve', self=null"
expect ctor "player created: 'Saint Steve'"
expect ctor "Saint Steve jumps"
expect ctor "[ctor-mod] making '', self=null"
expect ctor "intercept@HEAD    <- ctor-mod"
JAVA_OPTS=-Dctor.cancel=true run_with ctor-cancel "$T/ctor-mod.jar"
expect_code ctor-cancel 1
expect ctor-cancel "is a constructor, which can't be cancelled"

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

# --- redirect: a call inside a method, replaced at the call site ------------------------------
run_with redirect "$T/redirect-mod.jar"
expect_code redirect 0
expect redirect "title=redirected/Mr. Steve"                        # static call + virtual call, both replaced
expect redirect "~Steve JUMPS"                                       # void JDK calls: the right overload; wider types
expect redirect "redirects net.minecraft.world.entity.player.Player.ticks()I in net.minecraft.world.entity.player.Player#title, but that method never makes that call"
expect redirect "[replaces net.minecraft.world.entity.player.Player.getName()Ljava/lang/String;]"
expect redirect "badge=!evetS~"                                      # a new, made by a factory instead
expect redirect "[replaces new java.lang.StringBuilder(Ljava/lang/String;)]"
expect redirect "status=gone"                                        # an inherited method, named by its declaring class
expect redirect "[replaces net.minecraft.world.entity.Entity.isAlive()Z]"
expect_not redirect "NO MIRACLE OCCURRED"                            # naming Player::getName loads nothing early
expect redirect "[FakeMinecraft] done"

run_with redirect-hooked "$M/hello-mod.jar" "$T/redirect-mod.jar"   # a redirect and a hook in one method
expect_code redirect-hooked 0
expect redirect-hooked "[hello-mod] hop, bytecode patch for Steve"
expect redirect-hooked "~Steve JUMPS"

run_with redirect-clash "$T/redirect-mod.jar" "$T/redirect-clash.jar"
expect_code redirect-clash 1
expect redirect-clash "redirect-mod"
expect redirect-clash "redirect-clash"
expect redirect-clash "Only one mod can replace a call."

# --- both at once: a -javaagent and the loader's main class (or Resurrection) ------------------
both_ways() {   # both_ways <name> <main class>
    local dir="$ROOT/build/test-runs/$1"
    rm -rf "$dir" && mkdir -p "$dir/mods" && cp "$M/hello-mod.jar" "$dir/mods/"
    out="$(cd "$dir" && "$JAVA" -javaagent:"$ROOT/build/miracle-loader.jar" \
        -cp "$ROOT/build/miracle-loader.jar:$ROOT/build/fake-minecraft.jar" "$2" --username Steve 2>&1)"; code=$?
}
for m in io.github.hronosin.miracle.MiracleMain io.github.hronosin.miracle.Resurrection; do
    both_ways "twice-${m##*.}" "$m"
    expect_code "twice-${m##*.}" 0
    expect "twice-${m##*.}" "already prays here as a Java agent, so the main class only hands over to net.minecraft.client.main.Main"
    expect "twice-${m##*.}" "[hello-mod] hop, bytecode patch for Steve"
    expect "twice-${m##*.}" "[FakeMinecraft] done"
    expect_not "twice-${m##*.}" "already revealed"
done

# --- Resurrection: started on an older Java, the game rises again in Java 25 -----------------
OLD_JAVA="${MIRACLE_TEST_OLD_JAVA:-$(ls -d /usr/lib/jvm/java-21*/bin/java /usr/lib/jvm/java-17*/bin/java 2>/dev/null | head -1)}"
NEW_HOME="$("$JAVA" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.home = //p')"
res() {   # res <name> <java> [extra JVM options...]: the fake game through Resurrection
    local name="$1" java="$2"; shift 2
    local dir="$ROOT/build/test-runs/$name"
    rm -rf "$dir" && mkdir -p "$dir/mods" && cp "$M/hello-mod.jar" "$dir/mods/"
    out="$(cd "$dir" && env -u JAVA_HOME "$java" "$@" -cp "$ROOT/build/miracle-loader.jar:$ROOT/build/fake-minecraft.jar" \
        io.github.hronosin.miracle.Resurrection --username Steve 2>&1)"; code=$?
}
res resurrect-new "$JAVA"
expect_code resurrect-new 0
expect resurrect-new "[FakeMinecraft] done"
expect_not resurrect-new "Resurrecting"
if [ -n "$OLD_JAVA" ]; then
    MIRACLE_JAVA="$NEW_HOME" res resurrect "$OLD_JAVA" -Dmiracle.javaSearch=explicit -Dmiracle.test=kept
    expect_code resurrect 0
    expect resurrect "and MiracleLoader needs Java 25 or newer."
    expect resurrect "[Miracle] Resurrecting the game in Java"
    expect resurrect "[hello-mod] hop, bytecode patch for Steve"      # the mod ran, in the new Java
    expect resurrect "[FakeMinecraft] done"
    MIRACLE_JAVA="$(dirname "$(dirname "$OLD_JAVA")")" res resurrect-none "$OLD_JAVA" -Dmiracle.javaSearch=explicit
    expect_code resurrect-none 1
    expect resurrect-none "No Java 25 or newer was found, so there is no miracle today."
    expect resurrect-none "Found, but too old:"
    expect_not resurrect-none "[FakeMinecraft]"
else
    echo "  (no Java older than 25 around: Resurrection's relaunch not tested)"
fi

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

# --- direct calls: the same results the old way (a static call with an id per call) --------
# --- layers: the context's lanes and the full merge agree ---------------------------------------
for calls in true false; do
    JAVA_OPTS=-Dmiracle.directCalls=$calls run_with lanes-$calls "$T/lanes-mod.jar"
    expect_code lanes-$calls 0
    expect lanes-$calls "jumpPower=4.5"                                 # set 1, * 3, + 0.5: (1 + 0.5) * 3
    expect lanes-$calls "score=7"                                       # the same hook's second set
    expect lanes-$calls "Steve takes 15.0 damage from lanes"            # (10 + 1) * 2 = 22, clamped to 15
    expect lanes-$calls "move 3 1.0 false x -> 4.0"
    expect lanes-$calls "ticks=5"
    expect_not lanes-$calls "[FakeMinecraft] BOOM"
done

JAVA_OPTS=-Dmiracle.directCalls=false run_with stack3-old "$T/intercept-mod.jar" "$T/stack-a.jar" "$T/stack-b.jar"
expect_code stack3-old 0
expect stack3-old "jumpPower=1.56"
expect stack3-old "ticks=50"
expect stack3-old "Steve takes 5.0 damage from reduced zombie"
expect_not stack3-old "[FakeMinecraft] BOOM"
JAVA_OPTS=-Dmiracle.directCalls=false run_with badtype-old "$T/badtype-mod.jar"
expect badtype-old "thrown by a hook of mod 'badtype-mod'"
if [ -f "build/test-runs/stack/dump/net/minecraft/world/entity/player/Player.class" ]; then
    dumped="$("${JAVA_HOME:+$JAVA_HOME/bin/}javap" -c -p build/test-runs/stack/dump/net/minecraft/world/entity/player/Player.class)"
    grep -qE "InvokeDynamic #[0-9]+:(interceptReturn|interceptHead|fire):" <<< "$dumped" && ! grep -q "HookDispatch.interceptReturn" <<< "$dumped" \
        && pass=$((pass + 1)) || { fail=$((fail + 1)); echo "FAIL [direct]: patched code doesn't use direct call sites"; }
    dumped="$("${JAVA_HOME:+$JAVA_HOME/bin/}javap" -c -p build/test-runs/stack3-old/dump/net/minecraft/world/entity/player/Player.class)"
    grep -q "HookDispatch.interceptReturn" <<< "$dumped" && ! grep -qE "InvokeDynamic #[0-9]+:(interceptReturn|interceptHead|fire):" <<< "$dumped" \
        && pass=$((pass + 1)) || { fail=$((fail + 1)); echo "FAIL [direct]: directCalls=false still makes call sites"; }
else
    fail=$((fail + 1)); echo "FAIL [direct]: no dump to inspect"
fi

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
   "$T/clash-a.jar" "$T/clash-hi.jar" "$T/fly-mod.jar" "$T/wings-mod.jar" "$T/redirect-mod.jar" "$BAKED/"
out="$("$JAVA" -jar build/miracle-bake.jar --native fake=build/fake-minecraft.jar \
        --obf fake-obf=build/fake-minecraft-obf.jar,test-fixtures/fake-obf-game/mappings.txt "$BAKED"/*.jar 2>&1)"
expect bake "[bake] intercept-mod.jar"
expect bake "MISSING 1:"
expect bake "RGCT target net.minecraft.world.entity.player.Player#fly"
expect bake "not baked. Drop this version, or add a fallback for what's missing (fallback/fake-obf/src in the mod's sources)."
[ "$(grep -c "game references translated.*, baked" <<< "$out")" -eq 8 ] && pass=$((pass + 1)) \
    || { fail=$((fail + 1)); echo "FAIL [bake]: expected 8 baked mods"; echo "$out"; }
expect bake "[bake] wings-mod.jar (1 classes, fallbacks for [fake-obf])"
expect bake "5 game references translated, 1 fallback method(s) + 1 added, baked"
expect bake "note: added test.wings.WingsMod#label(Lnet/minecraft/world/entity/player/Player;)Ljava/lang/String;"

# --- OSHI through a library: a mod class extends a library class that extends a game class ----
cp "$T/heir-lib.jar" "$T/heir-mod.jar" "$BAKED/"
out="$("$JAVA" -jar build/miracle-bake.jar --native fake=build/fake-minecraft.jar \
        --obf fake-obf=build/fake-minecraft-obf.jar,test-fixtures/fake-obf-game/mappings.txt "$BAKED/heir-mod.jar" 2>&1)"
expect bake-heir-blind "into libraries unchecked"           # without --lib, the baker can't see through heir-lib
out="$("$JAVA" -jar build/miracle-bake.jar --lib "$BAKED/heir-lib.jar" --native fake=build/fake-minecraft.jar \
        --obf fake-obf=build/fake-minecraft-obf.jar,test-fixtures/fake-obf-game/mappings.txt \
        "$BAKED/heir-lib.jar" "$BAKED/heir-mod.jar" 2>&1)"
expect_not bake-heir "into libraries unchecked"
expect bake-heir "[bake] heir-mod.jar"
GAME_JAR="$ROOT/build/fake-minecraft-obf.jar" run_with obf-heir "$BAKED/heir-lib.jar" "$BAKED/heir-mod.jar"
expect_code obf-heir 0
expect obf-heir "[heir-mod] Arthur scores 8 and is blessed"
run_with heir-native "$BAKED/heir-lib.jar" "$BAKED/heir-mod.jar"
expect heir-native "[heir-mod] Arthur scores 8 and is blessed"

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

# --- OSHI fallbacks with nested classes: whole replacements, renamed anonymous ones, orphans dropped
cp "$T/nest-mod.jar" "$BAKED/"
out="$("$JAVA" -jar build/miracle-bake.jar --native fake=build/fake-minecraft.jar \
        --obf fake-obf=build/fake-minecraft-obf.jar,test-fixtures/fake-obf-game/mappings.txt "$BAKED/nest-mod.jar" 2>&1)"
expect bake-nest "fake-obf  obfuscated    ok, 1 game references translated, 1 fallback method(s), 3 fallback class(es), baked"
expect bake-nest "note: added class test.nest.NestMod\$fallback\$fake_obf\$1"
expect bake-nest "note: replaced class test.nest.NestMod\$Feathers"
expect bake-nest "note: added class test.nest.NestMod\$Step"
expect bake-nest "note: dropped test.nest.NestMod\$1 (only replaced code used it)"
expect bake-nest "note: dropped 2 lambda bodies in test.nest.NestMod (only replaced code used them)"  # not kept()'s
GAME_JAR="$ROOT/build/fake-minecraft-obf.jar" run_with obf-nest "$BAKED/nest-mod.jar"
expect_code obf-nest 0
expect obf-nest "[nest-mod] a fallback's anonymous walk, calm step by step / no feathers in this version / kept calm"
run_with nest-native "$BAKED/nest-mod.jar"
expect_code nest-native 0
expect nest-native "[nest-mod] an anonymous flight / feathers / kept calm"
expect nest-native "Alex flies"

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

GAME_JAR="$OBF" run_with obf-redirect "$BAKED/redirect-mod.jar"
expect_code obf-redirect 0
expect obf-redirect "title=redirected/Mr. Steve"                     # Player::motd baked to o.a::g
expect obf-redirect "~Steve JUMPS"
expect obf-redirect "badge=!evetS~"
expect obf-redirect "status=gone"                                    # Entity::isAlive baked to o.b::k, called as o.a.k()
expect obf-redirect "[replaces o.a.c()Ljava/lang/String;]"

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
expect yukari "  Yukari, from a gap: \""
out="$(cd "$CLI_HOME" && MIRACLE_YUKARI=0 "$JAVA" -jar "$ROOT/build/miracle.jar" smite 2>&1)"
expect_not yukari-asleep "Yukari"

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
# A running game holds its world's session.lock: lighting needs --anyway, resting refuses.
"$JAVA" test-fixtures/LockHolder.java "$CLI_HOME/holy-hops/run/server-26.2/world/session.lock" > "$CLI_HOME/lock.out" 2>&1 &
holder=$!
for _ in $(seq 1 50); do grep -q holding "$CLI_HOME/lock.out" 2>/dev/null && break; sleep 0.2; done
pcli bonfire
expect_code bonfire-busy 1
expect bonfire-busy "The game is running in run/server-26.2/world"
pcli grace --anyway
expect_code grace-anyway 0
expect grace-anyway "lighting anyway, as asked"
expect grace-anyway "SITE OF GRACE DISCOVERED"
pcli bonfire rest
expect_code bonfire-rest-busy 1
expect bonfire-rest-busy "would save over the world you rest at"
kill "$holder" 2>/dev/null; wait "$holder" 2>/dev/null
pcli bonfire list
expect_code bonfire-free 0

pcli exorcise
expect_code exorcise-dry 0
expect exorcise-dry "run/server-26.2/logs"
expect exorcise-dry "Say the words to cast them out: miracle exorcise --yes"
# A leftover from a wipe Windows didn't let finish: swept up by the next one.
mkdir -p "$CLI_HOME/holy-hops/run/server-26.2/logs.old-deadbeef/stuck" && touch "$CLI_HOME/holy-hops/run/server-26.2/logs.old-deadbeef/stuck/x.log"
pcli exorcise --yes
expect exorcise "The power of Miracle compels you!"
[ ! -e "$CLI_HOME/holy-hops/run/server-26.2/logs.old-deadbeef" ] && [ -z "$(ls -d "$CLI_HOME"/holy-hops/run/server-26.2/logs.old-* 2>/dev/null)" ] \
    && pass=$((pass + 1)) || { fail=$((fail + 1)); echo "FAIL [exorcise-sweep]: leftovers stayed"; }
[ ! -e "$CLI_HOME/holy-hops/run/server-26.2/logs" ] && [ -e "$CLI_HOME/holy-hops/run/server-26.2/world/level.dat" ] \
    && [ -d "$CLI_HOME/holy-hops/run/server-26.2/bonfires" ] && pass=$((pass + 1)) \
    || { fail=$((fail + 1)); echo "FAIL [exorcise]: took the wrong things"; }

pcli scribe entity ghoul --model
expect_code scribe-model 0
expect scribe-model "wrote assets/holy_hops/geo/ghoul.geo.json"
expect scribe-model "wrote assets/holy_hops/textures/entity/ghoul.png"
expect scribe-model ".sculpted().spawnEgg()"
out="$("$JAVA" -cp build/miracle-loader.jar:build/miracle-toolchain.jar io.github.hronosin.miracle.toolchain.SelfTest \
    --geometry "$CLI_HOME/holy-hops/resources/assets/holy_hops/geo/ghoul.geo.json" 2>&1)"; code=$?
expect_code scribe-model-reads 0
expect scribe-model-reads "geometry 64x64: body, rightLeg, leftLeg, head, rightArm, leftArm"
out="$("$JAVA" -cp build/miracle-loader.jar:build/miracle-toolchain.jar io.github.hronosin.miracle.toolchain.SelfTest \
    --geometry examples/hallelujah/resources/assets/hallelujah/geo/heretic.geo.json 2>&1)"; code=$?
expect heretic-model "geometry 64x64: body, rightLeg, leftLeg, head, rightArm, leftArm, horn, book, horn_r1"
pcli scribe item wafer --model
expect_code scribe-model-item 1
expect scribe-model-item "--model is for entities"

# --- ascend: everything short of the network ------------------------------------------------
pcli ascend
expect_code ascend-where 1
expect ascend-where "ascend where? miracle ascend modrinth, or miracle ascend github"
pcli ascend modrinth --no-build
expect_code ascend-nothing 1
expect ascend-nothing "Nothing to offer: build/holy-hops-0.1.0.jar doesn't exist."
mkdir -p "$CLI_HOME/holy-hops/build"
( cd "$CLI_HOME" && rm -rf jarroot && mkdir -p jarroot/META-INF/miracle \
    && printf 'baked = ["1.21.11"]\nchecked = ["26.2", "26.3"]\n' > jarroot/META-INF/miracle/bake.toml \
    && "${JAVA_HOME:+$JAVA_HOME/bin/}jar" --create --file holy-hops/build/holy-hops-0.1.0.jar -C jarroot . )
pcli ascend modrinth --no-build --dry-run
expect_code ascend-noproject 1
expect ascend-noproject "Which Modrinth project?"
pcli ascend modrinth --no-build --dry-run --project holy-hops -m "Amen"
expect_code ascend-dry 0
expect ascend-dry "Minecraft:  26.3, 26.2, 1.21.11  (what the bake checked or baked, nothing more)"
expect ascend-dry '"game_versions": ["26.3", "26.2", "1.21.11"]'
expect ascend-dry '"loaders": ["miracle"]'
expect ascend-dry '"changelog": "Amen"'
expect ascend-dry '"version_type": "release"'
pcli ascend github --no-build --dry-run --repo me/holy-hops --type beta
expect_code ascend-gh-dry 0
expect ascend-gh-dry "repository: me/holy-hops, tag v0.1.0"
expect ascend-gh-dry '"prerelease": true'
out="$(cd "$CLI_HOME/holy-hops" && env -u MODRINTH_TOKEN MIRACLE_HOME="$CLI_HOME/cache" "$JAVA" -jar "$ROOT/build/miracle.jar" \
    ascend modrinth --no-build --project holy-hops 2>&1)"; code=$?
expect_code ascend-notoken 1
expect ascend-notoken "MODRINTH_TOKEN isn't set."

cli zandatsu "$ROOT/build/test-mods/needs-lib.jar"
expect_code zandatsu 0
expect zandatsu "BLADE MODE. Cutting needs-lib.jar"
expect zandatsu "Spine:      org.test.needslib.NeedsLib"
expect zandatsu "ZANDATSU! 1 class(es) taken. Rules of Nature."
expect zandatsu "Reaches for: nothing outside the game"
cli zandatsu "$ROOT/build/test-mods/nosy-mod.jar"
expect zandatsu-reach "Reaches for (what its code names; reflection can hide more, and naming isn't misusing):"
expect zandatsu-reach "! starts processes"
expect zandatsu-reach "in org.test.nosy.Nosy: java.lang.ProcessBuilder"
expect zandatsu-reach "! uses the network"
expect zandatsu-reach "java.net.http.HttpClient"
expect zandatsu-reach "  writes, moves or deletes files"
expect zandatsu-reach "java.nio.file.Files.writeString"
expect zandatsu-reach "java.lang.System.exit"
if [ -f "$ROOT/build/hallelujah.jar" ]; then   # built only when a real Minecraft jar is around
    cli zandatsu "$ROOT/build/hallelujah.jar"
    expect zandatsu-hallelujah "Creation.shrine"
    expect zandatsu-hallelujah "Creation.reliquary"
    expect zandatsu-hallelujah "Creation.vision"
    expect zandatsu-hallelujah "Vision.caption"
    expect zandatsu-hallelujah "Baked for:  1.21.11"
fi
cli zandatsu "$ROOT/build/test-mods/patron-lib.jar"
expect zandatsu-patches "Patches:    Player"

cli fabric
expect fabric-alias "'fabric' is not a miracle command."
cli fast --seconds soon
expect_code fast-bad 1
expect fast-bad "HERESY: --seconds wants a whole number of seconds, not 'soon'"
cli genesis "9Holy Relics!" --minecraft 26.2
grep -q 'id = "holy-relics"' "$CLI_HOME/9Holy Relics!/miracle.mod.toml" && pass=$((pass + 1)) \
    || { fail=$((fail + 1)); echo "FAIL [genesis-id]: trailing punctuation kept in the id"; }
pcli pray altar --no-build
expect_code pray-bad-side 1
expect pray-bad-side "HERESY: pray what? client or server, not 'altar'"

cli dictionary --list
expect dictionary-empty "No dictionaries yet."

cli genesis --templates
expect templates "grace       Elden Ring"
cli genesis stylish-mod --template stylish --minecraft 26.2
expect_code template 0
grep -q 'depends = \["miracle-toolchain>=1.0.0"\]' "$CLI_HOME/stylish-mod/miracle.mod.toml" \
    && grep -q "Smokin' Sexy Style" "$CLI_HOME/stylish-mod/src/com/example/stylishmod/StylishMod.java" \
    && ! grep -q "__" "$CLI_HOME/stylish-mod/src/com/example/stylishmod/StylishMod.java" && pass=$((pass + 1)) \
    || { fail=$((fail + 1)); echo "FAIL [template]: bad stylish project"; }
cli genesis nope --template bloodborne
expect_code template-bad 1
expect template-bad "No template called 'bloodborne'."

pcli scribe item holy_wafer
expect_code scribe-item 0
expect scribe-item "wrote assets/holy_hops/items/holy_wafer.json"
expect scribe-item "wrote assets/holy_hops/textures/item/holy_wafer.png"
pcli scribe block altar --title "Altar of Miracles"
expect scribe-block "wrote data/holy_hops/loot_table/blocks/altar.json"
expect scribe-block "wrote assets/holy_hops/blockstates/altar.json"
R="$CLI_HOME/holy-hops/resources"
grep -q '"item.holy_hops.holy_wafer": "Holy Wafer"' "$R/assets/holy_hops/lang/en_us.json" \
    && grep -q '"block.holy_hops.altar": "Altar of Miracles"' "$R/assets/holy_hops/lang/en_us.json" \
    && [ "$(head -c 8 "$R/assets/holy_hops/textures/block/altar.png" | od -An -tx1 | tr -d ' \n')" = "89504e470d0a1a0a" ] \
    && pass=$((pass + 1)) || { fail=$((fail + 1)); echo "FAIL [scribe]: lang or texture wrong"; }
pcli scribe item holy_wafer
expect scribe-again "kept  assets/holy_hops/items/holy_wafer.json (exists; --force to overwrite)"
pcli scribe potion x
expect_code scribe-bad 1
expect scribe-bad "HERESY: scribe what?"

# sounds: a mono Ogg Vorbis is copied as it is; anything else needs ffmpeg, hidden from it here
SND="$CLI_HOME/sounds" && rm -rf "$SND" && mkdir -p "$SND"
{ printf 'OggS\000\002'; printf '\000%.0s' $(seq 20); printf '\001\036\001vorbis\000\000\000\000\001'; printf '\000%.0s' $(seq 30); } > "$SND/chime.ogg"
{ printf 'OggS\000\002'; printf '\000%.0s' $(seq 20); printf '\001\023OpusHead\001\002'; printf '\000%.0s' $(seq 30); } > "$SND/opus.ogg"
printf 'RIFF....WAVEfmt ' > "$SND/bell.wav"
nopath() { out="$(cd "$CLI_HOME/holy-hops" && PATH=/nonexistent MIRACLE_HOME="$CLI_HOME/cache" "$JAVA" -jar "$ROOT/build/miracle.jar" "$@" 2>&1)"; code=$?; }
nopath scribe sound chime "$SND/chime.ogg"
expect_code scribe-sound 0
expect scribe-sound "wrote assets/holy_hops/sounds/chime.ogg (Ogg Vorbis, mono, copied as it is)"
expect scribe-sound "It is sung. holy_hops:chime is a sound now"
nopath scribe sound chime "$SND/chime.ogg" --title "Chimes ring"
expect scribe-sound-variant "wrote assets/holy_hops/sounds/chime_2.ogg"
expect scribe-sound-variant "[chime, 2 variants]"
grep -q '"subtitles.holy_hops.chime": "Chimes ring"' "$R/assets/holy_hops/lang/en_us.json" \
    && grep -q '"name": "holy_hops:chime_2"' "$R/assets/holy_hops/sounds.json" \
    && cmp -s "$SND/chime.ogg" "$R/assets/holy_hops/sounds/chime.ogg" \
    && pass=$((pass + 1)) || { fail=$((fail + 1)); echo "FAIL [scribe-sound]: sounds.json, subtitle or file wrong"; }
nopath scribe sound bell "$SND/bell.wav"
expect_code scribe-sound-no-ffmpeg 1
expect scribe-sound-no-ffmpeg "Can't convert bell.wav: that needs ffmpeg"
expect scribe-sound-no-ffmpeg "winget install Gyan.FFmpeg"
[ ! -e "$R/assets/holy_hops/sounds/bell.ogg" ] && [ ! -e "$R/assets/holy_hops/sounds/bell.ogg.part" ] \
    && pass=$((pass + 1)) || { fail=$((fail + 1)); echo "FAIL [scribe-sound-no-ffmpeg]: left something behind"; }
nopath scribe sound theme "$SND/opus.ogg" --music
expect_code scribe-sound-opus 1
expect scribe-sound-opus "Can't convert opus.ogg"
pcli scribe sound bell
expect_code scribe-sound-bad 1
expect scribe-sound-bad "scribe sound <name> <file>"
pcli scribe item wafer2 --music
expect scribe-sound-music "--music is for sounds"
unset MIRACLE_IMPATIENT

# --- the library's own self-test (Telepathy's scrolls, litanies, Inquisition) -------------------
if [ -f build/miracle-toolchain.jar ]; then
    out="$("$JAVA" -cp build/miracle-loader.jar:build/miracle-toolchain.jar io.github.hronosin.miracle.toolchain.SelfTest 2>&1)"
    code=$?
    expect_code selftest 0
    expect selftest " 0 failed"
    expect selftest "ok    names never cross the wire"
    expect selftest "ok    a missing mod is named"
    expect selftest "ok    one-sided mods are nobody's business"
fi

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
expect depends "[needs-lib] icons: dep-lib assets/psalm.png (3594 bytes), patron-lib null"
expect depends "patron-lib.jar: icon = \"nope.png\", but the jar has no such file. Faceless, then."
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
expect too-old "needs-new-lib needs miracle >= 99, but miracle 1.6.0 is here. Update it."

run_with ghost-dep "$T/needs-ghost.jar"
expect_code ghost-dep 1
expect ghost-dep "needs-ghost needs holy-grail, which is not in the mods folder."
expect ghost-dep "needs-ghost needs miracle-toolchain, which is not in the mods folder. It's the MiracleToolChain library"

run_with imposter "$T/imposter-mod.jar"
expect_code imposter 1
expect imposter "the id 'miracle' belongs to the loader itself."

run_with cycle "$T/cycle-a.jar" "$T/cycle-b.jar"
expect_code cycle 1
expect cycle "These mods depend on each other in a circle: cycle-a -> cycle-b -> cycle-a."

# --- entangles: soft dependencies -------------------------------------------------------------
run_with entangles "$T/entangler.jar" "$T/z-partner.jar" "$T/y-old.jar" "$T/w-needy.jar"
expect_code entangles 0
expect entangles "[entangler] order: [z-partner, a-entangler, w-needy, y-old]"
expect entangles "[entangler] entangled: [z-partner], z-partner's: [], nobody's: []"
expect entangles "Entangler (a-entangler 0.1.0), entangled with z-partner"
expect entangles "a-entangler entangles y-old >= 2.0, but y-old 1.0.0 is here: not entangled with it."
expect entangles "a-entangler entangles w-needy, but w-needy already needs a-entangler (directly or through others)"
expect_not entangles "absent-mod"
run_with entangles-alone "$T/entangler.jar"
expect_code entangles-alone 0
expect entangles-alone "[entangler] entangled: [], z-partner's: [], nobody's: []"
run_with self-tangle "$T/self-tangle.jar"
expect_code self-tangle 1
expect self-tangle "self-tangle entangles itself."

# --- reach: what mods name outside the game; the raw graphics rule -------------------------------
run_with reach "$T/nosy-mod.jar"
expect_code reach 0
expect reach "Reach: nosy-mod starts processes, uses the network, can end the game itself (System.exit, halt). (miracle zandatsu shows where.)"
expect reach "[nosy] launched"
expect_not reach "writes, moves or deletes files"
run_with rawgl "$T/raw-gl.jar"
expect_code rawgl 0
expect rawgl "raw-gl calls OpenGL directly (org.lwjgl.opengl.GL11.glFinish in org.test.rawgl.RawGl)"
expect rawgl "This game is set to OpenGL, so it can work here; on the other backend it won't."
expect rawgl "Graphics: OpenGL (no options.txt yet; this version has OpenGL only)"
expect rawgl "[raw-gl] launched"
JAVA_OPTS="-Dmiracle.rawGraphics=refuse" run_with rawgl-refuse "$T/raw-gl.jar" "$T/nosy-mod.jar"
expect_code rawgl-refuse 1
expect rawgl-refuse "These mods call OpenGL or Vulkan directly, and -Dmiracle.rawGraphics=refuse:"
expect rawgl-refuse "raw-gl (org.lwjgl.opengl.GL11.glFinish in org.test.rawgl.RawGl)"
expect_not rawgl-refuse "[raw-gl] launched"
JAVA_OPTS="-Dmiracle.rawGraphics=allow" run_with rawgl-allow "$T/raw-gl.jar"
expect_code rawgl-allow 0
expect_not rawgl-allow "calls OpenGL directly ("
JAVA_OPTS="-Dmiracle.rawGraphics=maybe" run_with rawgl-bad "$T/raw-gl.jar"
expect_code rawgl-bad 1
expect rawgl-bad "-Dmiracle.rawGraphics=maybe: that's warn, refuse or allow."
run_with reach-quiet "$T/dep-lib.jar"
expect_not reach-quiet "Reach:"

# --- miracle.lock: pinned patches, diffs, strictness --------------------------------------------
# run_in <dir> <mod jars...>: like run_with, but keeps the folder (and its miracle.lock) between runs.
run_in() {
    local dir="$1"; shift
    rm -rf "$dir/mods" && mkdir -p "$dir/mods"
    for j in "$@"; do cp "$j" "$dir/mods/"; done
    # shellcheck disable=SC2086
    out="$(cd "$dir" && "$JAVA" ${JAVA_OPTS:-} -cp "$ROOT/build/miracle-loader.jar:$ROOT/build/fake-minecraft.jar" \
        io.github.hronosin.miracle.MiracleMain --username Steve 2>&1)"
    code=$?
}

# graphics backend: the player's word, or the game's own options.txt; Vulkan only from 26.2
GB="$ROOT/build/test-runs/backend" && rm -rf "$GB" && mkdir -p "$GB"
printf 'version:4786\npreferredGraphicsBackend:"vulkan"\nfov:0.0\n' > "$GB/options.txt"
JAVA_OPTS="-Dmiracle.gameVersion=26.3" run_in "$GB" "$T/raw-gl.jar" "$T/nosy-mod.jar"
expect_code backend-vulkan 0
expect backend-vulkan "Graphics: Vulkan (options.txt)"
expect backend-vulkan "[nosy] backend: vulkan"
expect backend-vulkan "This game is set to Vulkan: it won't work here."
JAVA_OPTS="-Dmiracle.gameVersion=26.3 -Dmiracle.backend=opengl" run_in "$GB" "$T/nosy-mod.jar"
expect backend-said "Graphics: OpenGL (-Dmiracle.backend)"
expect backend-said "[nosy] backend: opengl"
printf 'preferredGraphicsBackend:"default"\n' > "$GB/options.txt"
JAVA_OPTS="-Dmiracle.gameVersion=26.3" run_in "$GB" "$T/raw-gl.jar" "$T/nosy-mod.jar"
expect backend-default "Graphics: the game's own pick at start (OpenGL or Vulkan) (options.txt)"
expect backend-default "[nosy] backend: default"
expect backend-default "If the game picks Vulkan at start, it won't work."
printf 'preferredGraphicsBackend:"vulkan"\n' > "$GB/options.txt"
JAVA_OPTS="-Dmiracle.gameVersion=26.1.2" run_in "$GB" "$T/nosy-mod.jar"
expect backend-old "Graphics: OpenGL (options.txt; this version has OpenGL only)"
JAVA_OPTS="-Dmiracle.backend=metal" run_in "$GB" "$T/nosy-mod.jar"
expect_code backend-bad 1
expect backend-bad "-Dmiracle.backend=metal: that's auto (the game's own setting), opengl or vulkan."
LOCK_DIR="$ROOT/build/test-runs/lock"
rm -rf "$LOCK_DIR"
run_in "$LOCK_DIR" "$M/hello-mod.jar"
expect_code lock-first 0
expect lock-first "miracle.lock: pinned what 1 mod(s) patch"
lock="$(cat "$LOCK_DIR/miracle.lock" 2>/dev/null)"
out="$lock"
expect lock-file "mod hello-mod 0.1.0"
expect lock-file "  net.minecraft.world.entity.player.Player#jumpFromGround @HEAD [observes]"
run_in "$LOCK_DIR" "$M/hello-mod.jar"
expect lock-same "miracle.lock: every mod patches exactly what it did when pinned."
run_in "$LOCK_DIR" "$M/hello-mod.jar" "$M/chaos-mod.jar"
expect_code lock-diff 0
expect lock-diff "miracle.lock: what the mods patch has changed since it was pinned:"
expect lock-diff "chaos-mod 0.1.0 (new)"
expect lock-diff "  + net.minecraft.world.entity.player.Player raw [whole class]"
expect lock-diff "miracle.lock: pinned the new state."
JAVA_OPTS=-Dmiracle.lock=strict run_in "$LOCK_DIR" "$M/hello-mod.jar"
expect_code lock-strict 1
expect lock-strict "chaos-mod 0.1.0 (gone)"
expect lock-strict "miracle.lock is strict (-Dmiracle.lock=strict), so nothing starts"
out="$(cat "$LOCK_DIR/miracle.lock")"
expect lock-strict-keeps "mod chaos-mod 0.1.0"
JAVA_OPTS=-Dmiracle.lock=off run_in "$LOCK_DIR" "$M/hello-mod.jar"
expect_code lock-off 0
expect_not lock-off "miracle.lock:"
JAVA_OPTS=-Dmiracle.lock=maybe run_in "$LOCK_DIR" "$M/hello-mod.jar"
expect_code lock-bad-mode 1
expect lock-bad-mode "-Dmiracle.lock=maybe: that's not a mode. update, strict or off."

# --- the same games with MiracleLoader as a Java agent -------------------------------------
# (AGENT=1 ./test.sh runs every game test this way.)
AGENT=1 run_with agent-hello "$M/hello-mod.jar" "$M/chaos-mod.jar"
expect_code agent-hello 0
expect agent-hello "praying for a miracle, as a Java agent"
expect agent-hello "[hello-mod] hop, bytecode patch for Steve"
expect agent-hello "Handing over to the game's own main. Amen."
AGENT=1 GAME_JAR="$OBF" run_with agent-obf "$BAKED/hello-mod.jar"
expect_code agent-obf 0
expect agent-obf "uses its variant baked for"
expect agent-obf "[hello-mod] constructor done, self is a Player: true"
AGENT=1 run_with agent-badtype "$T/badtype-mod.jar"
expect_code agent-badtype 1
expect agent-badtype "NO MIRACLE OCCURRED"
expect agent-badtype "thrown by a hook of mod 'badtype-mod'"
if [ -n "$OLD_JAVA" ]; then
    out="$("$OLD_JAVA" -javaagent:"$ROOT/build/miracle-loader.jar" -cp "$ROOT/build/fake-minecraft.jar" \
        net.minecraft.client.main.Main 2>&1)"; code=$?
    expect_code agent-old-java 1
    expect agent-old-java "MiracleLoader (as a Java agent) needs Java 25 or newer"
fi

# --- genesis --horizon --------------------------------------------------------------------------
cli genesis event-test --horizon
expect_code genesis-horizon 0
out="$(cat "$CLI_HOME/event-test/miracle.mod.toml")"
expect genesis-horizon 'depends = ["miracle-toolchain>='
expect genesis-horizon '"event-horizon>='
cli genesis stoic --ascetic --horizon
expect_code genesis-horizon-ascetic 1
expect genesis-horizon-ascetic "an ascetic doesn't cross event horizons"

# --- Event Horizon's self-test (math, shapes, scheduler: no game needed) ------------------------
if [ -f build/event-horizon.jar ]; then
    out="$("$JAVA" -cp build/miracle-loader.jar:build/miracle-toolchain.jar:build/event-horizon.jar \
        io.github.hronosin.miracle.horizon.SelfTest 2>&1)"; code=$?
    expect_code horizon-selftest 0
    expect horizon-selftest "failed"
    expect_not horizon-selftest "FAIL:"
    # Lensing against every vanilla shader, when a classic and a 26.3 client are in the real cache
    CORPUS="${MIRACLE_HOME:-$HOME/.cache/miracle}/minecraft/versions"
    if [ -f "$CORPUS/26.2/client.jar" ] && [ -f "$CORPUS/26.3/client.jar" ]; then
        out="$("$JAVA" -cp build/miracle-loader.jar:build/miracle-toolchain.jar:build/event-horizon.jar \
            io.github.hronosin.miracle.horizon.SelfTest "$CORPUS/26.2/client.jar" "$CORPUS/26.3/client.jar" 2>&1)"; code=$?
        expect_code horizon-corpus 0
        expect horizon-corpus " 0 failed"
        expect_not horizon-corpus "FAIL:"
    fi
fi

# --- scriptorium: IDE files, when Minecraft 26.3 is in the real cache ------------------------
REAL_CACHE="${MIRACLE_HOME:-$HOME/.cache/miracle}"
if [ -f "$REAL_CACHE/minecraft/versions/26.3/client.jar" ]; then
    IDE="$ROOT/build/test-ide"
    rm -rf "$IDE" && mkdir -p "$IDE"
    (cd "$IDE" && "$JAVA" -jar "$ROOT/build/miracle.jar" genesis scribe-test --minecraft 26.3 > /dev/null 2>&1)
    mkdir -p "$IDE/scribe-test/fallback/1.21.11/src"
    out="$(cd "$IDE/scribe-test" && "$JAVA" -jar "$ROOT/build/miracle.jar" scriptorium 2>&1)"; code=$?
    expect_code scriptorium 0
    expect scriptorium "The scriptorium is ready for"
    expect scriptorium "fallbacks are modules of their own: 1.21.11"
    out="$(cat "$IDE/scribe-test/.idea/scribe-test.iml" "$IDE/scribe-test/.classpath" "$IDE/scribe-test/.idea/runConfigurations/Pray_client.xml" \
        "$IDE/scribe-test/.gitignore" 2>&1)"
    expect scriptorium-iml 'miracle-toolchain-sources.jar!/'
    expect scriptorium-classpath 'kind="lib" path="'
    expect scriptorium-run '<option name="PROGRAM_PARAMETERS" value="pray client" />'
    expect scriptorium-ignore ".idea/"
    # The class path it writes is the one that compiles the mod.
    cp="$(sed -n 's/.*kind="lib" path="\([^"]*\)".*/\1/p' "$IDE/scribe-test/.classpath" | paste -sd:)"
    if "$JAVAC_BIN" -d "$IDE/out" -cp "$cp" $(find "$IDE/scribe-test/src" -name '*.java') > /dev/null 2>&1; then
        pass=$((pass + 1)); else fail=$((fail + 1)); echo "FAIL [scriptorium-compiles]"; fi
else
    echo "(scriptorium tests skipped: no Minecraft 26.3 in $REAL_CACHE)"
fi

# --- consecrate: installing into a (fake) Prism Launcher ------------------------------------
PRISM="$ROOT/build/test-prism"
rm -rf "$PRISM" && mkdir -p "$PRISM/instances/Pure 26/minecraft" "$PRISM/instances/fabric-one"
printf '{"components":[{"uid":"net.minecraft","version":"26.3","important":true}],"formatVersion":1}' \
    > "$PRISM/instances/Pure 26/mmc-pack.json"
printf 'InstanceType=OneSix\nname=Pure Twenty-Six\n' > "$PRISM/instances/Pure 26/instance.cfg"
printf '{"components":[{"uid":"net.minecraft","version":"1.21.11"},{"uid":"net.fabricmc.fabric-loader"}]}' \
    > "$PRISM/instances/fabric-one/mmc-pack.json"
cli consecrate --prism "$PRISM" --list
expect consecrate-list "Pure 26   (\"Pure Twenty-Six\")"
cli consecrate --prism "$PRISM" "pure twenty-six" --examples
expect_code consecrate 0
expect consecrate "consecrated in \"Pure 26\" (Minecraft 26.3)"
out="$(cat "$PRISM/instances/Pure 26/patches/io.github.hronosin.miracle.json" "$PRISM/instances/Pure 26/mmc-pack.json" 2>&1;
       ls "$PRISM/instances/Pure 26/libraries" "$PRISM/instances/Pure 26/minecraft/mods" 2>&1)"
expect consecrate-patch '"mainClass": "io.github.hronosin.miracle.Resurrection"'
expect consecrate-pack '"cachedName": "MiracleLoader"'
expect consecrate-pack '"important": true'
expect consecrate-files "miracle-loader-$(sed -n 's/.*VERSION = "\(.*\)";/\1/p' tools/cli/src/io/github/hronosin/miracle/cli/Miracle.java).jar"
expect consecrate-files "title-mod.jar"
cli consecrate --prism "$PRISM" --list
expect consecrate-marked "[consecrated]"
cli consecrate --prism "$PRISM" fabric-one
expect_code consecrate-fabric 1
expect consecrate-fabric "already has net.fabricmc.fabric-loader"
cli consecrate --prism "$PRISM" --uninstall "Pure 26"
expect consecrate-undo "MiracleLoader removed from \"Pure 26\""
out="$(cat "$PRISM/instances/Pure 26/mmc-pack.json"; ls "$PRISM/instances/Pure 26/patches" "$PRISM/instances/Pure 26/libraries")"
expect_not consecrate-undo-clean "miracle"

echo
echo "passed: $pass, failed: $fail"
[ "$fail" -eq 0 ]
