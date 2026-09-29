# MiracleLoader

> Forge hammers. Fabric stitches. Miracle just happens.

A mod loader for Minecraft Java Edition 26.x that works *by miracle*. Well, technically: since 26.1 the game ships unobfuscated, and half the pain that Gradle plugins, mappings and remapping existed to solve has simply evaporated. We take that and run with it, shamelessly.

- **No toolchain.** A mod is `javac`, `jar` and one `miracle.mod.toml`. No Gradle, no Loom, no five-minute project syncs.
- **No mixins.** Instead there's **RGCT**, the Runtime Game Class Transformer, built on the JDK's own ClassFile API. Zero dependencies. Actually zero.
- **No magic, in the bad sense.** Who patched what is printed at startup. Who crashed the game is written in the crash report.

For those who'd rather not write everything from scratch, there's **MiracleToolChain**: a command line that creates, builds and runs mods, and a library mod with events, merge-ready game values, commands, configs and resource loading. The library is an ordinary mod with no special privileges, so anything it can do, you can do too.

> **Status: 0.2.0.** Runs on real Minecraft 26.x (client and server) and, through baked variants, on obfuscated 1.21.11. RGCT hooks observe, change or cancel game methods, and when several mods hook the same method their effects merge by fixed rules instead of overwriting each other. MiracleToolChain's `miracle` command creates, builds and runs mods with no Gradle in sight, and its library covers the common cases without naming a single game method.

The full contract of the toolchain, the build and the library is in the [specification](docs/SPEC.md).

---

## Quick start: making a mod

```bash
git clone https://github.com/Hronosin/MiracleLoader && cd MiracleLoader
ln -s "$PWD/miracle" ~/.local/bin/miracle     # the MiracleToolChain command line

miracle genesis holy-hops                     # a new mod project. Let there be mod.
cd holy-hops
miracle pray client                           # compile, bake, and play it
```

That's the whole setup. No Gradle, no IDE plugin, no launcher: `miracle` downloads Minecraft, its libraries and assets from Mojang (cached in `~/.cache/miracle`), compiles the mod, runs the game with MiracleLoader and your mod, and logs you in offline. Details in [MiracleToolChain](#miracletoolchain).

## Quick start: hacking on the loader

You need **JDK 25+** (Minecraft 26.x requires it anyway). On Fedora: `sudo dnf install java-25-openjdk-devel`.

```bash
./build.sh   # build the loader, the fake game and the example mods
./run.sh     # run the fake game with the example mods
./test.sh    # smoke tests (including baking for a fake obfuscated game)
```

With several JDKs installed: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./build.sh`.

The MiracleToolChain library and the real-game examples compile against Minecraft itself, so `build.sh` builds them only when it finds a 26.x client: from Prism Launcher, from the toolchain's own cache (anything `miracle pray` ever ran), or from `MC_JAR=... MC_LIBS=...`.

## How it launches

Put the loader on the class path next to the game and use `MiracleMain` as the main class instead of the game's:

```bash
java -cp miracle-loader.jar:minecraft.jar:<libraries> \
     io.github.hronosin.miracle.MiracleMain <game arguments>
```

The launcher can leave everything else as it is. Mods are loaded from `./mods`.

| Property | Default | Purpose |
|---|---|---|
| `-Dmiracle.target` | `net.minecraft.client.main.Main` | the game's main class (`net.minecraft.server.Main` for servers) |
| `-Dmiracle.modsDir` | `mods` | mods folder |
| `-Dmiracle.gameClasspath` | JVM class path minus the loader | if the game is not on the class path |
| `-Dmiracle.dump` | none | folder to write every patched class into, for debugging |

### Prism Launcher

```bash
./prism-install.sh --list                   # list instances
./prism-install.sh "Instance Folder Name"   # install (close Prism first)
./prism-install.sh --uninstall "Name"       # remove
```

The script puts the loader into the instance's `libraries/`, adds a MiracleLoader custom component (it overrides `mainClass`), and copies the example mods into `mods/`. The Flatpak install of Prism is found automatically; for anything else set `PRISM_DATA=/path/to/PrismLauncher`. Use a clean vanilla 26.x instance: Fabric or NeoForge in the same instance would fight over `mainClass`.

Tip: Mojang's 26.x launch arguments include `--enable-native-access=ALL-UNNAMED` and `--sun-misc-unsafe-memory-access=allow`. Adding them to the instance's JVM arguments silences LWJGL's startup warnings.

## Writing a mod without a toolchain

`miracle.mod.toml`:

```toml
id = "hello-mod"
name = "Hello Mod"
version = "0.1.0"
entrypoint = "com.example.hello.HelloMod"
authors = ["Hronosin"]
```

The code:

```java
public final class HelloMod implements MiracleMod {
    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
            .method("jumpFromGround")
            .atHead(self -> System.out.println("hop, bytecode patch"));
    }

    @Override
    public void onLaunch() {
        System.out.println("Miracles are real.");
    }
}
```

The entire build:

```bash
javac --release 25 -cp miracle-loader.jar:minecraft.jar -d classes HelloMod.java
cp miracle.mod.toml classes/
jar --create --file hello-mod.jar -C classes .
```

More examples live in `examples/`.

### Dependencies and libraries

```toml
depends = ["miracle-toolchain>=0.2.0", "some-other-mod", "miracle>=0.2.0"]
```

Every mod listed must be in `mods/`, at least that version if one is given, and loads before the mod that needs it. `miracle` means the loader itself. A missing or outdated dependency, or a circle of mods waiting for each other, stops the game before it starts, with every problem listed at once.

A mod with `library = true` and no `entrypoint` just brings classes for other mods. A library that needs to patch things for the mods using it can register patches in their name: `rgct.onBehalfOf("their-mod")`, allowed only for mods that depend on it. `io.github.hronosin.miracle.api.Mods` tells any mod who else is loaded, which jar a class came from, and which game is running.

### A real gameplay mod: `examples/dirt-diamonds`

The classic: 1 dirt → 1 diamond. The recipe is a plain JSON file in `data/dirt_diamonds/recipe/` inside the mod jar. The mod hooks `VanillaPackResourcesBuilder#build` and adds its own jar as one more root of the built-in vanilla pack, so the game finds the recipe as if it were its own. No recipe objects in code.

The same builder also assembles the client's vanilla *resource* pack, so the hook reads its `PackLocationInfo` argument and only touches the data pack.

On a dedicated 26.2 server the recipe count at startup goes from 1585 to 1586, and stays there after `/reload`.

### `examples/super-jump` + `examples/sprint-jump`: two mods, one method

Both hook `LivingEntity#getJumpPower`. super-jump multiplies players' jump power by 1.5; sprint-jump adds 0.1 while sprinting. Neither overwrites the other: RGCT merges them into `(vanilla + 0.1) × 1.5`, in whatever order they load.

```java
// super-jump
.interceptReturn(ctx -> {
    if (ctx.self() instanceof Player) ctx.multiplyReturnValue(1.5f);
})
// sprint-jump
.interceptReturn(ctx -> {
    if (ctx.self() instanceof Player p && p.isSprinting()) ctx.addToReturnValue(0.1f);
})
```

Easy to check in game:

| installed | jump height |
|---|---|
| vanilla | 1.25 blocks |
| sprint-jump, sprinting | 1.84 |
| super-jump | 2.59 (over a 2-block wall, no fall damage) |
| both, sprinting | 3.79 (onto a 3-block wall; landing on flat ground costs half a heart) |

Only with both mods can you sprint-jump onto a 3-block wall.

### Compiling against Minecraft

The mods above compile directly against the Minecraft client. `build.sh` finds the newest 26.x client jar Prism has downloaded, and reads Prism's metadata for that version to put exactly its libraries on the class path (Minecraft's classes extend Brigadier, DataFixerUpper and friends, so javac needs them too). Jars left over from other instances, like an old Forge, stay out. Elsewhere: `MC_JAR=/path/to/client.jar MC_LIBS=/folder/with/that/versions/jars ./build.sh`.

## RGCT

```java
rgct.target("a.b.SomeClass")
    .method("name")                 // every overload
    .atHead(self -> ...)            // observe: before the first instruction
    .atReturn(self -> ...)          // observe: before every normal return
    .and()
    .method("name", "(IF)Z")        // one exact overload
    .interceptHead(ctx -> ...)      // read/change arguments, or cancel the method
    .interceptReturn(ctx -> ...)    // read/replace the return value
    .and()
    .raw(classTransform);           // raw ClassFile API: you're on your own
```

### Observing: `atHead` / `atReturn`

The hook gets `self`, the object the method was called on. It is `null` for static methods and at the head of a constructor, where `this` is not initialized yet. Observing is nearly free: one static call, no allocation.

### Intercepting: `interceptHead` / `interceptReturn`

The hook gets a `HookContext`. It can read the call, and ask for **effects**:

| | `interceptHead` | `interceptReturn` |
|---|---|---|
| read | `self()`, `arg(i)` | `self()`, `arg(i)`, `returnValue()` |
| set | `setArg(i, v)` | `setReturnValue(v)` |
| stack | `addToArg`, `multiplyArg`, `clampArg` | `addToReturnValue`, `multiplyReturnValue`, `clampReturnValue` |
| skip | `cancel()`, `cancel(value)` | |

```java
// (method names are illustrative)
.method("damage").interceptHead(ctx -> ctx.multiplyArg(0, 0.5))                 // half damage
.method("explode").interceptHead(ctx -> ctx.cancel())                           // no explosions
.method("getMaxHealth").interceptReturn(ctx -> ctx.addToReturnValue(20))        // +10 hearts
```

### Layers: many mods, one method

This is the part MiracleLoader exists for. Hooks don't change the game directly. **Every hook on a method sees the same snapshot**, the values as vanilla produced them, and only declares what it wants. Once all hooks have run, RGCT merges their effects by fixed rules and applies the result once:

1. **set** replaces the base value.
2. **addTo** amounts are summed onto it.
3. **multiply** factors are all multiplied in.
4. **clamp** ranges are intersected and applied last.
5. **cancel**: if any mod cancels, the method doesn't run.

So `result = clamp((base + Σ adds) × Π factors)`, and it never depends on which mod loaded first. Three mods that give ×2, ×1.5 and +0.1 to jump power always give `(v + 0.1) × 3`.

**Conflicts.** Adding and multiplying always stack. Setting is exclusive, so when several mods `set` the same value (or `cancel` with a value):

- if they set the same value, fine;
- otherwise the highest `priority` wins: `.method("motd").priority(10).interceptReturn(...)`;
- a tie with different values stops the game with an error naming every mod involved:

```
RgctConflictException: RGCT conflict at Player#motd()Ljava/lang/String;, return value (priority 0):
'clash-a' sets "A", 'clash-b' sets "B". Mods that set the same value must agree, or one of them
needs a higher priority
```

Crashing sounds harsh, but the alternative is one mod silently not working and nobody knowing why.

**Checked before the game starts.** RGCT reads each hook's bytecode at startup (following calls into helper methods of the same class) to see which effects it can produce. The startup report shows it next to every hook, and possible conflicts are flagged before a single block is rendered:

```
[Miracle]   net.minecraft.world.entity.LivingEntity
[Miracle]     getJumpPower()F   intercept@RETURN  <- sprint-jump  [modifies return]
[Miracle]     getJumpPower()F   intercept@RETURN  <- super-jump  [modifies return]
[Miracle/WARN] RGCT: mods 'clash-a', 'clash-b' may all set its return value (...#motd) at priority 0.
               Fine while they agree; if they ever don't, the game stops with a conflict error.
```

It's a warning, not an error, because hooks usually set things conditionally (only for players, only while sprinting...) and two mods may never actually disagree.

Details:

- Primitives travel boxed: an `int` argument is an `Integer`, a `float` return is a `Float`. Plain Java casts unbox them: `(float) ctx.returnValue()`.
- `set` needs exactly the right type, and a wrong one (a `Double` where the game wants a `float`) fails right away with an error that names the method, the expected type and your mod. The stacking effects take any `Number`; integral results are rounded to the nearest value.
- Interception boxes the arguments into an `Object[]` on every call. Fine for most methods; for something called millions of times per tick, prefer observing.
- Not supported on constructor heads. Raw transforms are outside the layer system entirely.

### General

- If a hook throws, the exception keeps its type, but gets a note attached saying which mod is to blame.
- If a target method doesn't exist, RGCT warns you. The mod was most likely built for another game version.

### The one rule

**Don't touch game classes inside `transform()`.** Patches are still being collected at that point, so the class would load unpatched. The loader catches this and refuses to start, naming the mod. An honest crash right away beats a patch that silently didn't apply half an hour into a session.

## OSHI: Old School Hook Integration

Two things for people who still remember `ClassNode`s and mapping files.

### Bring your own tools: `rawBytes`

```java
rgct.target("net.minecraft.world.entity.player.Player").rawBytes(bytes -> {
    ClassNode node = new ClassNode();          // your ASM, shaded into your mod
    new ClassReader(bytes).accept(node, 0);
    // ... the monstrous tree surgery of your dreams ...
    ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
    node.accept(w);
    return w.toByteArray();
});
```

The loader stays dependency-free: whatever library you use, you bring it. `rawBytes` runs after every other patch on the class, is outside the layer system, and the startup report and log say who did it. Your library must read Java 25 class files (ASM 9.8+). A Mixin bridge would be a mod of its own built on this.

### Reversed mapping: one source, many versions

Minecraft 1.21.11 and older ship with scrambled names: `LivingEntity` is `chl`, `getJumpPower()` is `fF`, and short names get reused across overloads (`a`, `a`, `a`...). Other loaders translate the whole game to stable names at every launch. MiracleLoader does the opposite: **the mod is translated once, at build time, for every version you target**, and the loader just picks the right variant. Nothing is remapped at runtime.

You write and compile against readable names (an unobfuscated 26.x jar). Then:

```bash
tools/fetch-dictionary.sh 1.21.11   # Mojang's client jar + official mappings, into ~/.cache/miracle
tools/fetch-dictionary.sh 26.1.2    # unobfuscated versions: the jar is its own dictionary
./build.sh                          # compiles, then checks and bakes against every fetched dictionary
```

Before anything is baked, every reference the mod makes into the game is looked up in every version's dictionary at once:

```
[bake] dirt-diamonds.jar (1 classes)
         26.2      unobfuscated  ok, 12 game references present
         1.21.11   obfuscated    ok, 13 game references translated, baked
         26.1.2    unobfuscated  ok, 12 game references present
[bake] fly-mod.jar (1 classes)
         fake-obf  obfuscated    MISSING 1:
             - RGCT target net.minecraft.world.entity.player.Player#fly
             not baked. Drop this version, or add a fallback for what's missing.
```

- **Unobfuscated versions** are only checked. The jar records them as `checked`; on any other 26.x the loader still runs the mod, with a warning.
- **Obfuscated versions** get a variant under `META-INF/miracle/baked/<version>/`. It's used on exactly that version and no other, because obfuscated names differ between every two releases.
- Translated: class, method and field references, `instanceof`/casts, lambdas and method references (including game functional interfaces), methods of your classes that override game methods, and the strings of RGCT targets. `method("build")` becomes `method("a", "(Lazk;)Lazp;")`: a baked target always carries its exact descriptor, since `a` alone would hit every method called `a`. An overloaded target without a descriptor is a bake error.
- At startup the loader reads `version.json` from the game jar, picks the variant, and fails clearly if an obfuscated version has none. The report shows readable names next to the scrambled ones (a few lines baked in for RGCT targets only).

Tested on real dedicated servers: the same `dirt-diamonds`, `super-jump` and `sprint-jump` jars run on 26.2 as they are and on 1.21.11 from their baked variants (1470 → 1471 recipes).

### Fallback functions: filling the holes

When the check shows a hole, you don't fork the mod. You write a **fallback**: the one method that has to be different in that version, next to your normal sources.

`examples/jump-counter` shows your jump count above the hotbar. In 26.2 that's `player.sendOverlayMessage(msg)`; 1.21.11 doesn't have that method. The main source is written for 26.2:

```java
// src/com/example/jumpcounter/JumpCounter.java
public final class JumpCounter implements MiracleMod {
    // ... hook, counter ...
    static void show(ServerPlayer player, Component message) {
        player.sendOverlayMessage(message);
    }
}
```

and one file fills the hole for 1.21.11:

```java
// fallback/1.21.11/src/com/example/jumpcounter/JumpCounter.java
final class JumpCounter {
    static void show(ServerPlayer player, Component message) {
        player.displayClientMessage(message, true);
    }
}
```

```
[bake] jump-counter.jar (1 classes, fallbacks for [1.21.11])
         26.2      unobfuscated  ok, 8 game references present
         1.21.11   obfuscated    ok, 8 game references translated, 1 fallback method(s), baked
```

Without the fallback, the same check says `MISSING 1: method ServerPlayer#sendOverlayMessage(...)` and 1.21.11 isn't baked.

The fallback file is a **partial class** with the same name as the real one:

- a method with a body replaces the method of the same name and descriptor, or is added if there's none;
- a `native` method and any field are only declarations, so the file compiles: they refer to the real class's members (`static native void flap(String how);` calls the real `flap`);
- constructors and static initializers of the partial class are ignored; lambdas are fine (their synthetic methods are renamed so they can't collide), nested and anonymous classes aren't;
- classes that only exist in the fallback folder are added as they are.

Fallbacks are merged in before the dictionary check, so a version is baked only once they cover every hole. They work for unobfuscated versions too: then the variant is baked just for that version.

**What fallbacks compile against.** Code for 1.21.11 has to see 1.21.11's API, with readable names. miracle-bake builds that from the dictionary: `miracle-bake --api 1.21.11=client.jar,mappings.txt api.jar` writes a jar with every class, method, field, generic signature and nested class under its Mojang name, and method bodies replaced by `throw null`. `build.sh` makes it on demand, caches it next to the mappings, and compiles `fallback/<version>/src` against it. Add it to your IDE for that folder and you write old-version code with readable names.

Limits, honestly:

- MiracleLoader itself needs Java 25, so the oldest reachable versions are the ones that run on it (1.20.5+ in principle; 1.21.11 is what's tested). In Prism, set the instance's Java to 25.
- Mojang's mappings may be used for development but not redistributed: they stay in your cache and are only read. Mods carry just the handful of readable names their RGCT targets need.
- References into libraries (Brigadier, DataFixerUpper...) aren't obfuscated and aren't checked. Game names hidden in your own strings (reflection) aren't translated; only RGCT targets are.
- Official Mojang mappings only, for now.
- Fallbacks compile against the primary version's libraries (Brigadier, DFU...), not the target version's; that has been fine so far.

## MiracleToolChain

Forge's toolchain is huge and has everything you need, and plenty you don't. Ours aims for the same, minus the Gradle. One command, solemn names, boring aliases for scripts and people without a sense of humour:

| command | alias | does |
|---|---|---|
| `miracle genesis <name>` | `new` | creates a mod project: sources, `miracle.mod.toml`, `miracle.project.toml`, a sample mod using the library (`--ascetic`: plain RGCT) |
| `miracle bake` | `build` | compiles, adds fallbacks, then checks and bakes against every target |
| `miracle pray client` | `run` | bakes, then plays the mod: offline, singleplayer |
| `miracle pray server` | `run` | bakes, then hosts it (asks you to accept Mojang's EULA first, with `--eula`) |
| `miracle confess` | `doctor` | lists what's wrong with your setup and your Aura (RWBY), then absolves you anyway |
| `miracle scribe item\|block <name>` | `assets` | writes what a new item or block needs besides code: model definitions, models, a placeholder texture, the English name, and for blocks a blockstate and a loot table. Keeps existing files unless `--force`; `--title "Holy Wafer"` names it |
| `miracle bonfire [list\|rest [name]]` | `backup` | Dark Souls: checkpoints the worlds in `run/`; `rest` brings one back (the world you leave is kept too) |
| `miracle grace ...` | | the same, for the Tarnished |
| `miracle messages` | `todo` | Elden Ring: your TODO/FIXME/HACK/XXX comments as messages on the ground ("Try repent", "Be wary of the mixins") |
| `miracle zandatsu [jar]` | `inspect` | Metal Gear Rising: Blade Mode for a mod jar. What it patches, which library parts it uses, what it was baked for |
| `miracle exorcise [--yes]` | `clean` | casts out `build/`, logs and crash reports. Worlds, bonfires and configs are spared |

`bake` grades itself Devil May Cry style, from `D Dismal` to `SSS Smokin' Sexy Style!!`, by how long it took. A game that crashes under `pray` gets a proper `YOU DIED` and the path to its crash report, even when a dedicated server exits politely afterwards.

And the rituals nobody needs, because Forge's toolchain has plenty you don't need and ours shouldn't be outdone:

| command | does |
|---|---|
| `miracle gradle [--really]` | a faithful reenactment of a Gradle build: daemon, configuring, remapping, decompiling, `BUILD SUCCESSFUL in 5m 3s`. Builds nothing. `--really` takes the full five minutes |
| `miracle forge` | did you mean: miracle? (also answers to `neoforge`, `fabric`, `loom`) |
| `miracle heresy` | finds Forge, NeoForge, Fabric, Quilt, Mixin and Architectury imports in your sources and assigns penance |
| `miracle fast [--seconds 40]` | the Great Lent of the build: forty seconds of doing nothing, with feeling |
| `miracle tithe` | offers 10% of your cache to the heavens. Symbolically: deletes nothing |

In the game, operators get `/smite [reason]`: it crashes the server on purpose, the proper way (a crash report blaming `Smitten by MiracleToolChain: <reason>`, then a normal shutdown). `smite = false` in `config/miracle-toolchain.toml` turns it off.

### Templates

`miracle genesis <name> --template <t>` starts from a small, working mod instead of the default sample. All of them use the library, check on 26.2 and 26.3, and bake for 1.21.11 as they are:

| template | after | what |
|---|---|---|
| `aura` | RWBY | an Aura shield that soaks up hits until it breaks, then regenerates when you're out of combat. `/aura` |
| `stylish` | Devil May Cry | a style meter from D to SSS: variety and kills climb, taking hits drops a rank, idling drains it. `/style` |
| `zandatsu` | Metal Gear Rising | sneak-kills tear out full health ("cut and take"); nanomachines cancel a killing blow now and then. `/nanomachines` |
| `you-died` | Dark Souls | `YOU DIED` across the screen, a death tally that survives restarts, `VICTORY ACHIEVED` for bosses, and praising the sun. `/deaths` |
| `grace` | Elden Ring | "Rise, Tarnished" on joining; `/message` leaves a message on the ground for others to find, `/appraise` rates it; `GREAT ENEMY FELLED` |

`miracle genesis --templates` lists them.

A project is two small files:

```toml
# miracle.project.toml
minecraft = "26.3"                       # written and compiled against: unobfuscated, readable names
targets = ["26.3", "26.2", "1.21.11"]    # checked by bake; obfuscated ones get a baked variant
```

```
$ miracle bake
Baking Holy Hops 0.1.0 against Minecraft 26.3
  libraries for 26.3: 66/66 (66 downloaded)
  compiling main sources (1 file)
[bake] holy-hops-0.1.0.jar (1 classes)
         26.3      unobfuscated  ok, 3 game references present
         26.2      unobfuscated  ok, 3 game references present
         1.21.11   obfuscated    ok, 3 game references translated, baked
Baked: build/holy-hops-0.1.0.jar

$ miracle pray server --eula --version 1.21.11
Praying for Minecraft 1.21.11 in run/server-1.21.11 ...
[Miracle] Game: Minecraft 1.21.11 (obfuscated)
[Miracle] OSHI: holy-hops uses its variant baked for 1.21.11
```

- `pray` runs in `run/<side>-<version>/`, so worlds and settings survive between runs. `--version` runs any target; `--username` names your offline player; `--no-build` skips the bake; anything after `--` goes to the game.
- The first `pray server` writes a dev `server.properties`: bound to localhost, offline logins allowed, so `pray client` can join it.
- Everything Mojang-sourced is cached by hash: the first client run downloads about half a gigabyte of assets, later ones nothing.
- The toolchain has no dependencies either: its own JSON reader, javac through `javax.tools`, downloads through `java.net.http`.

Verified: `genesis`, `bake` and `pray server` end to end on 26.3 and 1.21.11, and `genesis` → `pray client` on a real desktop (Fedora, Wayland, AMD): the 26.3 client downloads, starts through MiracleLoader with the mod, and drops you into a fresh world as an offline player. The `401` and Realms errors in the log are just the game noticing you're offline.

Next for the toolchain: `miracle ide` (IDE project files, including readable API jars for fallbacks) and `miracle ascend` (publish to Modrinth).

## The MiracleToolChain library

Everything you need, and several things you don't, in `miracle-toolchain.jar`. Every part has a solemn name and a boring alias; they are the same class, pick whichever you can say with a straight face.

| part | alias | what |
|---|---|---|
| `Omens` | `Events` | things that happen: `serverStarted`, `serverStopping`, `serverTick`, `playerJoined`, `playerLeft`, `playerJumped`, `chat` (cancellable), `blockBroken` (cancellable), `entityHurt` (cancellable), `entityDied`, `clientTick` |
| `Blessings` | `Tweaks` | well-known values with merge rules: `jumpPower`, `movementSpeed`, `fallDamage`, `damageTaken` |
| `Sermons` | `ChatCommands` | commands, in plain Brigadier; they survive `/reload` |
| `Commandments` | `Config` | `config/<mod id>.toml`, written with defaults and comments the first time |
| `Scripture` | `Resources` | the `data/` and `assets/` in your jar, loaded as if they were the game's own |
| `Proclamations` | `Notices` | overlay lines, titles and broadcasts, the same on every version (the game renamed these; the packets stayed) |
| `Creation` | `Content` | new items and blocks, with block items and creative tabs; `miracle scribe` writes their models, placeholder textures, names and loot tables |
| `Telepathy` | `Networking` | messages between client and server: per-tick batches, hashed channel names, typed `Scroll`s, and the Inquisition watching for forged packets |
| `Gestures` | `Keybinds` | keys players can rebind in Controls, by name (`"G"`, `"LEFT_ALT"`, `"F6"`) |

```java
public final class Hallelujah implements MiracleMod {
    static final Telepathy.Channel PRAYER = Telepathy.channel("hallelujah:prayer");

    @Override
    public void onLaunch() {
        Commandments config = Commandments.mine();
        double jump = config.number("jump_multiplier", 1.25, "Player jump power. 1 = vanilla.");

        Omens.playerJoined(p -> p.sendSystemMessage(Component.literal("Welcome, pilgrim.")));
        Omens.chat((player, msg) -> msg.contains("creeper") ? Verdict.SMITE : Verdict.SPARE);

        Blessings.jumpPower().forPlayers().multiply(jump);
        Blessings.damageTaken().when(e -> e instanceof Cow).clamp(0, 2);

        Creation.item("holy_water", p -> new Item(p.stacksTo(16))).inTab("food_and_drinks");
        Creation.block("altar", p -> new Block(p.strength(2f))).inTab("functional_blocks");

        // Press G: the client asks, the server decides.
        Gestures.key("pray", "G", () -> PRAYER.toServer(new Scroll().writeString("hallelujah")));
        PRAYER.onServer((player, scroll) -> player.heal(4f));

        Sermons.preach(d -> d.register(Commands.literal("hallelujah").executes(c -> {
            Sermons.reply(c.getSource(), "Amen.");
            return 1;
        })));
    }
}
```

With `depends = ["miracle-toolchain"]` in `miracle.mod.toml`. That's `examples/hallelujah`, give or take a sanctuary where only operators may break blocks and a cooldown on prayers. Its textures, models, names and the altar's loot table came from `miracle scribe item holy_water` and `miracle scribe block altar`. `miracle genesis` starts every project like this (`--ascetic` for plain RGCT).

**Why `onLaunch()`, and the Prophecy.** A handler that takes a `ServerPlayer` loads the `ServerPlayer` class the moment the handler is created, and with it `Player`, `LivingEntity` and `Entity`. Do that in `transform()` and those classes can no longer be patched, by you or anyone. So handlers are added in `onLaunch()`, when the game classes are fair game. The patches they need must exist before that, though, and that's the Prophecy: at startup the library reads the classes of every mod that depends on it (reads, not loads) and sees which omens, values and commands each one uses. It patches exactly those methods, in that mod's name.

```
[Miracle] MiracleToolChain foresees for hallelujah: serverStarted, playerJoined, chat, blockBroken, jumpPower:multiply, fallDamage:multiply, sermons
[Miracle]   net.minecraft.world.entity.LivingEntity
[Miracle]     getJumpPower()F              intercept@RETURN  <- blessed  [modifies return]
[Miracle]     getJumpPower()F              intercept@RETURN  <- hallelujah  [modifies return]
```

Parts nobody uses patch nothing. The startup report, conflict checks and crash blame name your mod, not the library. Calling an omen from `transform()`, or from code the Prophecy couldn't read, fails with a message that says so. `Blessing.priority(n)` only matters for `set`, and wants `n` written as a number.

**Blessings merge.** A blessing is RGCT's layers with the targets filled in: `clamp((vanilla + adds) × factors)`, so ten mods multiplying jump power all get their way, and `set` only where you mean it. The library knows where each value lives in every supported version, including where vanilla computes it twice: a player's speed comes from a different method than a mob's, and `movementSpeed` covers both.

**Telepathy, briefly.** Each side collects what it has to say during a tick and sends it as one batch, in one custom payload packet, at the end of the tick. Channel names never cross the wire, only a hash of them; what crosses is data, never code, and a message can only reach a handler the receiving side registered itself. The server's Inquisition expects a client's batches numbered 1, 2, 3... and a few per tick at most; anything else (replayed, forged, flooded, garbage, unknown channels) is logged, and dropped or kicked as `config/miracle-toolchain.toml` says (`inquisition = "drop"`). It's a tripwire for crude packet injectors, not an anti-cheat: the server should still check everything it's told.

Every target was checked on 1.21.11, 26.1.2, 26.2 and 26.3, and the library jar carries a baked variant for 1.21.11 like any other mod. Verified on dedicated servers (26.3 and 1.21.11): the Prophecy's patches, commands before and after `/reload`, server omens, damage multiplied then clamped, a cow walking away from a 120-block fall, `entityHurt` and `entityDied` on mobs, all five templates together, `/smite`, new blocks placed and broken (with loot), new items summoned. And on real clients, 26.3 and 1.21.11, run headless (software OpenGL, an offscreen window) straight into a world: the chunk with the new blocks loads, the new items show in their creative tabs with their names, keys land in Controls and `options.txt` and fire when pressed, messages go client → server → client, and hand-forged packets get the Inquisition's attention while the honest ones pass. Players' eyes are still needed for textures, titles and meters.

## Lifecycle

1. Find mods in `mods/`, check their `depends`, and order them: dependencies first, otherwise by id, so load order never depends on the file system's mood.
   Read the game version from `version.json`, and put each mod's baked variant for it (if any) in front of the mod's own classes.
2. Call `transform(Rgct)` on every mod (libraries without an entrypoint have none). MiracleToolChain's Prophecy runs here.
3. Freeze RGCT, print the report of who layered what onto which method, and warn about possible conflicts.
4. Call `onLaunch()` on every mod. Game classes may be used from here on.
5. Run the game's `main`. Classes get patched as they load.

## Roadmap

- [x] Run the real 26.x client and server, Prism Launcher integration
- [x] Hook access to arguments and return values, cancellation
- [x] **Layers**: hooks declare effects that merge by fixed rules, so the result doesn't depend on mod order
- [x] Priorities as a tie-breaker, clear conflict errors naming the mods
- [x] Startup conflict check from the hooks' bytecode
- [x] OSHI: `rawBytes` for bring-your-own bytecode tools
- [x] OSHI: check against many versions' dictionaries at once, bake variants for obfuscated ones
- [x] OSHI: fallback functions, and readable API jars to compile them against
- [ ] Yarn dictionaries
- [x] Merge rules for well-known game values (MiracleToolChain's Blessings)
- [ ] `miracle.lock`: pin the startup analysis, so a mod update that changes behavior shows up as a diff
- [ ] Direct calls instead of the dispatcher when a method has a single hook
- [x] Mod dependencies in `miracle.mod.toml`, library mods, patching on behalf of dependents
- [x] **MiracleToolChain** command line: genesis, bake, pray client/server, confess
- [ ] MiracleToolChain: `ide`, `ascend` (publish)
- [x] MiracleToolChain library: events, well-known values, commands, configs, data and assets
- [x] MiracleToolChain library: registries (items, blocks), networking, keybinds
- [ ] MiracleToolChain library: block entities, menus, entities, custom rendering
- [x] MiracleToolChain specification ([docs/SPEC.md](docs/SPEC.md))

## License

Please read carefully: [EULA.md](EULA.md).
