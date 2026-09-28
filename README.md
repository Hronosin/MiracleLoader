# MiracleLoader

> Forge hammers. Fabric stitches. Miracle just happens.

A mod loader for Minecraft Java Edition 26.x that works *by miracle*. Well, technically: since 26.1 the game ships unobfuscated, and half the pain that Gradle plugins, mappings and remapping existed to solve has simply evaporated. We take that and run with it, shamelessly.

- **No toolchain.** A mod is `javac`, `jar` and one `miracle.mod.toml`. No Gradle, no Loom, no five-minute project syncs.
- **No mixins.** Instead there's **RGCT**, the Runtime Game Class Transformer, built on the JDK's own ClassFile API. Zero dependencies. Actually zero.
- **No magic, in the bad sense.** Who patched what is printed at startup. Who crashed the game is written in the crash report.

For those who'd rather not write everything from scratch, there will be **MiracleToolChain**: a separate library mod with events, registries and the rest of the Forge-style comforts. It will be an ordinary mod with no special privileges, so anything it can do, you can do too.

> **Status: 0.1.0-mvp.** Runs on real Minecraft 26.2: the client through Prism Launcher and the dedicated server. RGCT hooks can observe a method, change its arguments and return value, or cancel it outright; raw ClassFile transforms are there for everything else.

---

## Quick start

You need **JDK 25+** (Minecraft 26.x requires it anyway). On Fedora: `sudo dnf install java-25-openjdk-devel`.

```bash
./build.sh   # build the loader, the fake game and the example mods
./run.sh     # run the fake game with the example mods
./test.sh    # smoke tests
```

With several JDKs installed: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./build.sh`.

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

### A real gameplay mod: `examples/dirt-diamonds`

The classic: 1 dirt → 1 diamond. The recipe is a plain JSON file in `data/dirt_diamonds/recipe/` inside the mod jar. The mod hooks `VanillaPackResourcesBuilder#build` and adds its own jar as one more root of the built-in vanilla pack, so the game finds the recipe as if it were its own. No recipe objects in code.

The same builder also assembles the client's vanilla *resource* pack, so the hook reads its `PackLocationInfo` argument and only touches the data pack.

On a dedicated 26.2 server the recipe count at startup goes from 1585 to 1586, and stays there after `/reload`.

### `examples/super-jump`

Players jump 1.5x as fast, about 2.6 blocks high: over a 2-block wall, and still no fall damage on the way down. Mobs are unaffected. The whole mod:

```java
rgct.target("net.minecraft.world.entity.LivingEntity")
    .method("getJumpPower", "()F")
    .interceptReturn(ctx -> {
        if (ctx.self() instanceof Player) {
            ctx.setReturnValue((float) ctx.returnValue() * 1.5f);
        }
    });
```

### Compiling against Minecraft

Both mods above compile directly against the Minecraft client. `build.sh` finds the newest 26.x client jar Prism has downloaded, and reads Prism's metadata for that version to put exactly its libraries on the class path (Minecraft's classes extend Brigadier, DataFixerUpper and friends, so javac needs them too). Jars left over from other instances, like an old Forge, stay out. Elsewhere: `MC_JAR=/path/to/client.jar MC_LIBS=/folder/with/that/versions/jars ./build.sh`.

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

The hook gets a `HookContext`:

| | at the head | at a return |
|---|---|---|
| `self()` | yes (`null` if static) | yes |
| `arg(i)` / `setArg(i, v)` | changes what the method body sees | read the arguments; changes only affect later hooks |
| `returnValue()` / `setReturnValue(v)` | no | yes |
| `cancel()` / `cancel(value)` | skips the method (and its return hooks) | no |

```java
// (method names are illustrative)
.method("damage").interceptHead(ctx -> ctx.setArg(0, (float) ctx.arg(0) / 2f)) // half damage
.method("explode").interceptHead(ctx -> ctx.cancel())                          // no explosions
.method("getMaxHealth").interceptReturn(ctx -> ctx.setReturnValue(40f))        // double health
```

- Primitives travel boxed: an `int` argument is an `Integer`, a `float` return is a `Float`. Plain Java casts unbox them: `(float) ctx.returnValue()`.
- Setting the wrong type (a `Double` where the game expects a `float`) fails right away with an error that names the method, the expected type and your mod, instead of a mysterious crash deep inside the game.
- When several mods intercept the same method, their hooks run in mod-id order and each sees what the previous ones changed. Layered merging will replace this later without changing the API.
- Interception costs an `Object[]` of boxed arguments per call. Fine for most methods; for something called millions of times per tick, prefer observing.
- Not supported on constructor heads.

### General

- Every hook goes through a single dispatcher. That's where layers and effect merging will grow later (see the roadmap) without breaking the API.
- If a hook throws, the exception keeps its type, but gets a note attached saying which mod is to blame.
- If a target method doesn't exist, RGCT warns you. The mod was most likely built for another game version.

### The one rule

**Don't touch game classes inside `transform()`.** Patches are still being collected at that point, so the class would load unpatched. The loader catches this and refuses to start, naming the mod. An honest crash right away beats a patch that silently didn't apply half an hour into a session.

## Lifecycle

1. Find mods in `mods/` and sort them by id, so load order never depends on the file system's mood.
2. Call `transform(Rgct)` on every mod.
3. Freeze RGCT and print the report of who layered what.
4. Call `onLaunch()` on every mod.
5. Run the game's `main`. Classes get patched as they load.

## Roadmap

- [x] Run the real 26.x client and server, Prism Launcher integration
- [x] Hook access to arguments and return values, cancellation
- [ ] **Layers**: hooks don't mutate the game, they return effects that merge by rules, so the result doesn't depend on mod order
- [ ] Merge rules: declared by the target, by the mod, or inferred by heuristics from the hook's bytecode
- [ ] `miracle.lock`: inferred rules get pinned, modpacks stay reproducible
- [ ] Priorities as a tie-breaker, clear conflict errors
- [ ] Direct calls instead of the dispatcher when a method has a single hook
- [ ] Mod dependencies in `miracle.mod.toml`
- [ ] **MiracleToolChain**: events, registries, networking, configs

## License

Please read carefully: [EULA.md](EULA.md).
