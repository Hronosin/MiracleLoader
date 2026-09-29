# MiracleToolChain Specification

> Everything you need, and several things you don't. Specified.

| | |
|---|---|
| Version | 0.3.0 |
| Status | Draft. Describes the implementation at the commit it ships with; where they disagree, one of them has a bug. |
| Covers | the `miracle` command line, `miracle-bake`, the `miracle-toolchain` library, and the parts of MiracleLoader they rely on |

---

## Contents

1. [Scope and conventions](#1-scope-and-conventions)
2. [Components](#2-components)
3. [The project](#3-the-project)
4. [Environment](#4-environment)
5. [Commands](#5-commands)
6. [Baking](#6-baking)
7. [Running](#7-running)
8. [The mod jar and the loader](#8-the-mod-jar-and-the-loader)
9. [The library](#9-the-library)
10. [Templates](#10-templates)
11. [Supported game versions](#11-supported-game-versions)
12. [Versioning and stability](#12-versioning-and-stability)
13. [Known limits](#13-known-limits)
- [Appendix A: exit codes](#appendix-a-exit-codes)
- [Appendix B: names](#appendix-b-names)

---

## 1. Scope and conventions

MiracleToolChain is two things: a command line that creates, builds, checks and runs Minecraft mods for MiracleLoader, and a library mod that covers the common modding cases without the mod naming a game method. This document specifies both: what each command does and leaves behind, what the build produces, and what the library promises.

What it does not specify: RGCT's hook injection and layer merging (see the README, "RGCT" and "Layers"), and Minecraft itself.

**MUST**, **MUST NOT**, **SHOULD** and **MAY** are used as in RFC 2119. "The toolchain" means the `miracle` command line; "the library" means `miracle-toolchain.jar`; "the loader" means MiracleLoader. Game classes are written with their Mojang names (`net.minecraft.server.level.ServerPlayer`), whatever the running version calls them.

Every name comes in two forms: a solemn one and a boring alias. They are equivalent in every respect; Appendix B lists them.

## 2. Components

| artifact | what | depends on |
|---|---|---|
| `miracle-loader.jar` | MiracleLoader: discovery, RGCT, launching | the JDK |
| `miracle.jar` | the command line, with `miracle-bake` built in | the JDK, `miracle-loader.jar` (manifest `Class-Path`) |
| `miracle-bake.jar` | `miracle-bake` alone, for scripts and `build.sh` | the JDK |
| `miracle-toolchain.jar` | the library: an ordinary mod (id `miracle-toolchain`) | MiracleLoader ≥ 0.3.0, and the game |
| `miracle` | a shell wrapper that runs `build/miracle.jar`, building it first if it's missing | bash |

All of them MUST run on Java 25 or newer and MUST NOT need anything beyond the JDK: JSON, TOML, HTTP, compilation (`javax.tools`) and bytecode work (`java.lang.classfile`) are the JDK's or our own.

The toolchain finds the loader and the library **next to its own jar** (`miracle-loader.jar`, `miracle-toolchain.jar`), unless told otherwise (section 4.2). The library is needed only by projects that depend on it (section 3.2).

## 3. The project

### 3.1 Layout

A project is a folder with a `miracle.project.toml`. Commands that need a project look for that file in the current folder, then in each parent; the first folder that has it is the project.

```
my-mod/
  miracle.project.toml     what the toolchain reads (3.3)
  miracle.mod.toml         what the loader reads (3.2); copied into the jar as is
  src/                     Java sources, any package layout
  resources/               copied into the jar root (data/, assets/, anything)
  fallback/<version>/src/  fallback functions for one target version (6.4)
  build/                   output: classes/, <id>-<version>.jar         (made by bake)
  run/<side>-<version>/    game folders, one per side and version      (made by pray)
```

`build/` and `run/` are the toolchain's; everything else is the author's, and the toolchain MUST NOT change it, except where a command says so (`genesis` creates it, `bonfire rest` replaces a world).

### 3.2 `miracle.mod.toml`

| key | type | required | meaning |
|---|---|---|---|
| `id` | string | yes | `[a-z][a-z0-9_-]{1,63}`. Unique among loaded mods. `miracle` is reserved for the loader and refused. |
| `name` | string | no | display name; default: the id |
| `version` | string | no | default `0.0.0`. Compared as dotted numbers (8.2) |
| `entrypoint` | string | unless `library` | binary name of a class implementing `io.github.hronosin.miracle.api.MiracleMod` with a public no-arg constructor |
| `library` | boolean | no | `true`: no entrypoint; the mod only brings classes for other mods. It MUST be said explicitly, so a forgotten entrypoint is an error, not a library. |
| `authors` | string array | no | |
| `icon` | string | no | path of a square PNG inside the jar (from `resources/`), for launchers, mod lists and Modrinth; a leading `/` is ignored. A path the jar doesn't have is reported and dropped. |
| `depends` | string array | no | `"<id>"` or `"<id> >= <version>"` (spaces optional). Every entry MUST be satisfied or the game does not start (8.2). |

A mod that uses the library MUST list `"miracle-toolchain"` in `depends` (with or without a version). That is how the toolchain knows to compile against the library and ship it to `run/`, and how the library knows to prepare the mod's hooks (9.3).

### 3.3 `miracle.project.toml`

| key | type | required | meaning |
|---|---|---|---|
| `minecraft` | string | yes | the version the sources are written and compiled against. MUST be unobfuscated (26.1 or newer); an obfuscated one is rejected with a pointer to `targets`. |
| `targets` | string array | no | versions `bake` checks the mod against; default `[minecraft]`. Obfuscated targets get a baked variant (6.3). `minecraft` need not be listed; it is always checked. Entries are versions or patterns (below). |

**Target patterns.** `"26.*"` means every release whose id starts with `26.`; `">=1.21.11"` every release that compares greater or equal (dotted numbers, 3.4); `"latest"` the latest release. Patterns are expanded against Mojang's version list, releases only, whenever the toolchain needs the targets; so a project picks up new releases by itself. A pattern that matches nothing is a heresy. `bake` prints the expansion.

### 3.4 The TOML subset

Both files, `bake.toml`, and the library's config files use the same flat subset of TOML:

- one `key = value` per line; keys `[A-Za-z0-9_-]+`; a key MUST NOT repeat;
- values: `"strings"` (escapes `\" \\ \n \t`), `["arrays", "of", "strings"]` on one line (a trailing comma is allowed), `true`/`false`, integers (`42`, `-7`, `1_000`), decimals (`1.5`, `2e3`);
- `#` starts a comment, on its own line or after a value;
- no tables (`[section]`), no multi-line values, no dotted keys.

Anything else is a parse error that names the line.

**Versions** are compared number by number (`1.10` > `1.9`); missing parts count as 0, and anything after `-` or `+` is ignored.

## 4. Environment

### 4.1 The cache

Everything downloaded is cached under `$MIRACLE_HOME`, default `~/.cache/miracle`:

```
minecraft/version_manifest_v2.json      Mojang's version list, refreshed at most hourly
minecraft/versions/<v>/<v>.json         version metadata
minecraft/versions/<v>/client.jar
minecraft/versions/<v>/server-bundle.jar  Mojang's server bundler
minecraft/versions/<v>/server/...       the dedicated server, unpacked from it
minecraft/libraries/<maven path>        libraries, shared between versions
minecraft/assets/indexes, objects       sounds, languages (client runs)
dictionaries/<v>/client.jar             for baking: a copy of the client
dictionaries/<v>/mappings.txt           obfuscated versions only: Mojang's ProGuard mappings
dictionaries/<v>/api.jar                obfuscated versions only: readable stubs for fallbacks (6.4)
```

Rules:

- A file with a known SHA-1 MUST be verified after download and MUST NOT be downloaded again while its hash matches. A failed request is tried up to three times; a hash mismatch fails at once.
- The version list is fetched from `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json`. If that fails and a cached list exists, the cached list is used, however old.
- A version is **obfuscated** if and only if its metadata offers `client_mappings`.
- Mojang's mappings MUST stay in the cache. They are read, never copied into a mod or anywhere else.

### 4.2 Variables and properties

| name | kind | read by | effect |
|---|---|---|---|
| `MIRACLE_HOME` | env | toolchain | cache location (4.1) |
| `MIRACLE_DICTIONARIES` | env | toolchain | dictionaries location; default `$MIRACLE_HOME/dictionaries` |
| `MIRACLE_LOADER_JAR`, `-Dmiracle.loaderJar` | env, property | toolchain | the loader jar, instead of the one next to the toolchain |
| `MIRACLE_TOOLCHAIN_JAR`, `-Dmiracle.toolchainJar` | env, property | toolchain | the library jar, likewise |
| `MIRACLE_IMPATIENT=1` | env | toolchain | the rituals (5.4) skip every pause |
| `-Dmiracle.showCommand=true` | property | toolchain | `pray` prints the full game command line |

Toolchain properties (`-D...`) go to the JVM running `miracle.jar`; through the `miracle` wrapper, pass them in `JDK_JAVA_OPTIONS`, or use the environment variables.
| `JAVA_HOME` | env | `miracle` wrapper, `build.sh` | which Java runs the toolchain; `pray` starts the game with the same Java |
| `DISPLAY`, `WAYLAND_DISPLAY` | env | `confess` | whether `pray client` has somewhere to open a window |
| `-Dmiracle.target` | property | loader | the game's real main class (default `net.minecraft.client.main.Main`) |
| `-Dmiracle.modsDir` | property | loader | mods folder (default `mods`) |
| `-Dmiracle.gameClasspath` | property | loader | game jars, instead of the JVM class path |
| `-Dmiracle.dump` | property | loader | folder to write every patched class into |
| `-Dmiracle.gameVersion`, `-Dmiracle.obfuscated` | property | loader | override version detection (8.3) |
| `-Dmiracle.configDir` | property | library, templates | config folder (default `config`, relative to the game folder) |
| `-Dmiracle.lock` | property | loader | `update` (default), `strict` or `off` (8.7) |
| `-Dmiracle.lockFile` | property | loader | where `miracle.lock` lives (default: next to the mods folder) |

## 5. Commands

```
miracle <command> [arguments]
```

Every command has a solemn name and a boring alias; both MUST behave identically. Options are `--name` or `--name value` and MAY appear anywhere after the command. Unknown commands are refused (`'<x>' is not in the scripture`).

**Errors.** A user error (missing file, bad argument, unknown Minecraft version, wrong setup) is a *heresy*: one line, `HERESY: <what and how to fix it>`, on stderr, exit code 1, no stack trace. A network or disk failure prints `The heavens are silent: <reason>` and exits 1. An interrupted command exits 130. Options a command doesn't know are ignored.

### 5.1 Making mods

#### `genesis <name>` (alias `new`)

```
miracle genesis <name> [--package com.you.mod] [--minecraft <v>] [--ascetic | --template <t>]
miracle genesis --templates
```

Creates `./<name>/` as a new project. It MUST refuse if that folder exists and isn't empty.

- **id**: `<name>` lower-cased, every character outside `[a-z0-9_-]` replaced by `-`, leading non-letters and trailing `-`/`_` dropped, cut to 64 characters. Fewer than two characters left is a heresy. The folder keeps `<name>` as given.
- **class**: the id's `-`/`_`-separated words, capitalized and joined (`holy-hops` → `HolyHops`).
- **package**: `--package`, or `com.example.<id without non-alphanumerics>`.
- **minecraft**: `--minecraft`, or Mojang's latest release if it is unobfuscated, otherwise (or when Mojang can't be reached) `26.2`.
- **targets**: `["<minecraft>", "26.*", "1.21.11"]`.

Files created: `miracle.mod.toml` (version `0.1.0`, `authors` = the OS user name, and `depends = ["miracle-toolchain>=0.3.0"]` unless `--ascetic`), `miracle.project.toml` (with comments), `src/<package>/<Class>.java`, an empty `resources/`, `fallback/README.md`, `.gitignore` (`build/`, `run/`) and `README.md`.

The source file is one of:

- the default: a sample that touches each part of the library (a config value, a join greeting, a jump blessing, a `/<id>` command), everything in `onLaunch()`;
- `--ascetic`: plain RGCT, no library, no `depends` (a jump multiplier in `transform()`);
- `--template <t>`: one of the templates in section 10.

`--ascetic` and `--template` together are a heresy, as is an unknown template. `--templates` lists the templates and exits.

#### `bake` (alias `build`)

Compiles the project, adds fallbacks, packs the jar, then checks and bakes it against every target. Section 6 specifies the pipeline. The output is `build/<id>-<version>.jar`. The last line grades the bake (6.7).

#### `pray client|server` (alias `run`)

```
miracle pray client|server [--version <v>] [--username <name>] [--no-build] [--eula] [-- <game args>]
```

Bakes (unless `--no-build`), then runs the game with the loader and the mod. A side other than `client` or `server` is a heresy, raised before anything is built. Section 7 specifies the rest.

### 5.2 Taking care of things

#### `confess` (alias `doctor`)

Checks the setup and prints one line per check, `[ok]`, `[!!]`, or `[--]` for what doesn't apply (no project here):

1. the Java running the toolchain is 25 or newer, and has a compiler;
2. the loader jar is found; the library jar is found (informational: its absence is only a sin for projects that use it);
3. the cache: location, size, dictionaries present;
4. inside a project: the project, whether it uses the library (and the library is there), `minecraft` is unobfuscated, each target resolves, the last bake;
5. a display to open the client window on.

Then the Aura, the share of checks passed: `100%` "Semblance unlocked.", `≥75%` "Holding.", `≥40%` "Flickering.", less "Broken.". Then each sin again, with its penance. Exit 0 if there are no sins, else 1.

#### `bonfire` (alias `backup`), `grace`

```
miracle bonfire [light]          checkpoint the worlds
miracle bonfire list             list checkpoints, newest first, per run folder
miracle bonfire rest [<name>]    restore one (default: the newest not made by resting)
```

`grace` does the same operations in Elden Ring's words (`SITE OF GRACE DISCOVERED` instead of `BONFIRE LIT`, and so on).

- Works on every `run/<side>-<version>/` that has a world: `world/` for servers, `saves/` for clients (all of its worlds).
- **light** copies each world to `run/<side>-<version>/bonfires/<yyyy-MM-dd_HH-mm-ss>/` (with `-2`, `-3`... if that second is taken), skipping `session.lock`, and prints `BONFIRE LIT`.
- **rest** first moves the current world to `bonfires/<timestamp>_before-rest/`, then copies the chosen checkpoint in its place. It MUST NOT lose a world: the one being left is always kept. Exit 1 if there was nothing to rest at.
- A project with no worlds yet is a heresy.
- The game SHOULD be closed while lighting or resting; the toolchain doesn't check.

#### `messages` (alias `todo`)

Reads every `.java` file in the project (or the current folder outside a project), except under `build/` and `run/`, and prints each `TODO`, `FIXME`, `HACK` and `XXX` comment as a message on the ground, with its file and line:

| tag | message |
|---|---|
| `TODO` | `Try <text>` |
| `FIXME` | `Be wary of <text>` |
| `HACK` | `Could this be a hack? <text>` |
| `XXX` | `<text> ahead` |

A comment is `//`, `/*`, `*` or `#` followed by the tag; trailing `/*` and `*/` are trimmed; an empty text becomes "something". The "Appraised" count is a hash of the text: stable, and meaningless. Exits 0 (files are read as UTF-8; one that isn't is an I/O failure).

#### `zandatsu [jar]` (alias `inspect`)

Opens a mod jar (default: the project's last bake) without running anything and prints: name, id and version; entrypoint ("spine"); `depends`; class count; the game classes (simple names) it names in `rgct.target(...)` calls with a constant string; the library API it calls (public parts only); whether it brings OSHI hooks (`raw`, `rawBytes`); the versions it has baked variants and fallbacks for; and its `bake.toml`. A jar without a readable `miracle.mod.toml` exits 1; a missing jar is a heresy.

#### `exorcise [--yes]` (alias `clean`)

Inside a project, lists `build/`, and `logs/`, `crash-reports/` and `debug/` in every `run/` folder, with sizes. With `--yes`, deletes them. It MUST NOT delete worlds, bonfires, configs, sources or anything else. Exits 0.

#### `scribe item|block|entity <name>` (alias `assets`)

```
miracle scribe item <name> [--title "Holy Water"] [--force]
miracle scribe block <name> [--title "Altar of Miracles"] [--force]
miracle scribe entity <name> [--title "Heretic"] [--egg zombie | --no-egg] [--force]
```

Writes, under the project's `resources/`, what a new item, block or entity needs besides code (9.11). The namespace is the project's id with `-` as `_`:

| kind | files |
|---|---|
| item | `assets/<ns>/items/<name>.json` (model definition), `assets/<ns>/models/item/<name>.json` (`item/generated`), `assets/<ns>/textures/item/<name>.png` |
| block | `assets/<ns>/blockstates/<name>.json`, `assets/<ns>/models/block/<name>.json` (`block/cube_all`), `assets/<ns>/items/<name>.json` (the block's item uses the block model), `assets/<ns>/textures/block/<name>.png`, `data/<ns>/loot_table/blocks/<name>.json` (drops itself unless blown up) |
| entity | `data/<ns>/loot_table/entities/<name>.json` (drops nothing, edit to taste) and a spawn egg `<name>_spawn_egg`: `assets/<ns>/items/<name>_spawn_egg.json` pointing at vanilla's `minecraft:item/<egg>_spawn_egg` with `--egg <egg>`, or at its own `item/generated` model and placeholder texture without; `--no-egg` writes neither the egg nor the loot table (a projectile, say) |

All kinds add `item.<ns>.<name>`, `block.<ns>.<name>` or `entity.<ns>.<name>` to `assets/<ns>/lang/en_us.json` (entities with an egg also `item.<ns>.<name>_spawn_egg`, "<Name> Spawn Egg"), keeping its other entries; the name is `--title`, or the id's words capitalized. Textures are 16×16 placeholders coloured from a hash of the id (a gem for items and eggs, a framed tile for blocks). Existing files and entries MUST be kept unless `--force`; each file is reported as `wrote` or `kept`. Another kind, or `--egg` on anything but an entity, is a heresy.

#### `dictionary <versions>` (alias `mappings`)

```
miracle dictionary 1.21.11 "26.*" ">=26.2" latest
miracle dictionary --list
```

Fetches the dictionaries for the given versions and patterns (4.1): the client jar (hard-linked to the cached client where the file system allows, copied otherwise) and, for an obfuscated version, Mojang's mappings. Already-fetched files are verified by hash and not downloaded again. `bake` does this by itself for a project's targets; this command is for scripts (`build.sh` uses it) and for looking. Without arguments, or with `--list`, it lists the cached dictionaries.

### 5.3 Plumbing

| command | does |
|---|---|
| `classpath <v>` | prints the client jar and the libraries version `<v>` compiles against, `:`-separated, on the **last** line of output (download progress may come before it), downloading what's missing. Used by `build.sh`. |
| `version` (`--version`) | `MiracleToolChain <version>` |
| `help` (`--help`, `-h`, no command) | the command list |

### 5.4 Rituals

Commands nobody needs, kept on purpose. They MUST NOT change anything outside the terminal. `MIRACLE_IMPATIENT=1` removes their pauses.

| command | does | exit |
|---|---|---|
| `gradle [--really]` | prints a Gradle build (daemon, configuring, remapping, decompiling, progress bars, `BUILD SUCCESSFUL in 5m 3s`) over about 20 seconds, or 5 minutes with `--really`; then admits that nothing was built | 0 |
| `forge` (also `neoforge`, `fabric`, `loom`) | "'forge' is not a miracle command. Did you mean:" `miracle bake`, or `miracle gradle` for those who miss the waiting | 1 |
| `heresy` | finds `import` lines from `net.minecraftforge`, `net.neoforged`, `net.fabricmc`, `org.quiltmc`, `org.spongepowered.asm` and `dev.architectury` in the project's `src/` (or the current folder, outside a project), and assigns one Hail Mary each | 0 if none, else 1 |
| `fast [--seconds N]` | does nothing for N seconds (default 40), counting down | 0 |
| `tithe` | reports the cache size and "offers" a tenth of it; deletes nothing | 0 |

## 6. Baking

### 6.1 Stages

`miracle bake` MUST do the following, in order, and stop at the first failure:

1. Resolve `minecraft`; if it is obfuscated, stop with a heresy.
2. Fetch its client jar and its compile libraries (no natives).
3. Wipe and recreate `build/classes/`.
4. Compile `src/**/*.java` with `--release 25`, UTF-8, all lint except `serial`, `path`, `classfile` and `processing`, annotation processing off. Class path: the loader, the library (if the project depends on it), the libraries, the client.
5. Copy `resources/` and `miracle.mod.toml` into `build/classes/`.
6. For each `fallback/<v>/` folder (sorted): compile `fallback/<v>/src` against the loader, the library (if used), the libraries, the main classes, and **the readable API of `<v>`** (6.4), into `build/classes/META-INF/miracle/fallback/<v>/`.
7. Pack `build/classes/` into `build/<id>-<version>.jar`.
8. Expand the targets (3.3), fetch each one's dictionary (4.1, `dictionary`), and run `miracle-bake` on the jar, with `minecraft` as a native version and every other target as native or obfuscated, as Mojang says.

A compiler error stops the bake (heresy), as does a `fallback/<v>/` folder without `src/`. A missing reference found in stage 8 does not: see 6.5. A fallback folder for a version that isn't a target is compiled (so that version is downloaded) but only noted at bake time.

### 6.2 Checking

For every target version `miracle-bake` builds a dictionary (the version's class, method and field names, with inheritance), then walks every reference the mod makes to a game class, method or field:

- **native** (unobfuscated) versions: each reference MUST exist in that version, directly or through a superclass or interface.
- **obfuscated** versions: each reference is translated through the mappings, then MUST exist.

RGCT targets are references too: on every version, the target class and method MUST exist. References into the JDK, the loader, the library and third-party libraries (Brigadier, DataFixerUpper...) are not checked.

### 6.3 Variants

For each target where every reference resolves, `miracle-bake` writes a **variant**, a full copy of the mod's classes, to `META-INF/miracle/baked/<v>/`, when the target is obfuscated, or when it is unobfuscated but has fallbacks (6.4) that change code. An unobfuscated target without fallbacks needs no variant: the main classes run as they are.

In an obfuscated variant, the following are translated through the mappings:

- class names, member names and descriptors, in code, invokedynamic (lambda) sites and method handles;
- method names that override game methods;
- **RGCT strings**: a string constant directly followed by `Rgct.target(String)` is a class name, and the `method(name[, descriptor])` right after it names a method of that class. Both are translated. A target given without a descriptor is pinned to the exact descriptor of the one method with that name, because obfuscated names are reused across overloads. If the name is overloaded, or the class isn't a string constant, the target is a hole on obfuscated versions ("give the descriptor").

Generic `Signature` attributes and local-variable debug tables are dropped from obfuscated variants. Strings anywhere else (reflection, config) are **not** translated.

Each obfuscated variant also gets `rgct-names.txt`, so the loader's report can print readable names: after a `#` header line, `<obf.class> = <readable.class>` for each target class and `<obf.class>#<obfName><obfDescriptor> = <readableName>` for each target method.

### 6.4 Fallback functions

When a target lacks something the mod uses, the author writes a *fallback*: a partial class with the same name, in `fallback/<v>/src/`, compiled against that version's readable API (for an obfuscated version, `dictionaries/<v>/api.jar`: stubs made from the mappings, with generics and inner classes, method bodies `throw null`). When baking `<v>`, before checking:

- a method **with a body** replaces the main class's method of the same name and descriptor, or is added if there is none;
- a `native` method, or a field, is a declaration only, there so the file compiles; it is dropped;
- constructors, static initializers and `$deserializeLambda$` of the partial class are dropped;
- lambdas in fallback methods get their synthetic methods renamed (`lambda$fallback$<v>$...`, with every non-alphanumeric character of `<v>` replaced by `_`) so they cannot collide with the main class's;
- a replacing method keeps the real method's access flags; a `static` method can't replace an instance one, or the reverse;
- a class that exists only in the fallback folder is added as is, unless it is a nested or anonymous class of a main class (which can't be added that way).

A broken fallback prints `BAD FALLBACK` with the reason, and that version is neither baked nor checked.

A fallback that covers every hole makes the version bakeable. Fallbacks stay in the jar under `META-INF/miracle/fallback/<v>/`.

### 6.5 Results

For each target `miracle-bake` prints `ok` with the number of references checked (and fallbacks applied), or `MISSING` with the list. It then writes `META-INF/miracle/bake.toml`:

```toml
# Written by miracle-bake. Which game versions this jar is ready for.
baked = ["1.21.11"]                  # versions with a variant (6.3)
checked = ["26.3", "26.2"]           # unobfuscated versions every reference was found in
```

A target with holes is listed in neither. An unobfuscated target with a variant is listed in both. `miracle bake` MUST still produce the jar; the holes are reported, and the loader will refuse (obfuscated) or warn (unobfuscated) on that version (8.3). `miracle-bake --strict` exits 1 instead; the toolchain does not use it.

Jars are rewritten in place. Nothing from the mappings goes into the jar except the readable names in `rgct-names.txt`.

### 6.6 `miracle-bake` directly

```
java -jar miracle-bake.jar [--strict] (--native V=CLIENT_JAR | --obf V=CLIENT_JAR,MAPPINGS_TXT)... MOD.jar...
java -jar miracle-bake.jar --api V=CLIENT_JAR,MAPPINGS_TXT OUT.jar
```

Usage errors exit 2.

### 6.7 Style

The last line of `bake` grades it by wall time, Devil May Cry style:

| time | rank |
|---|---|
| < 2.5 s | `SSS  Smokin' Sexy Style!!  Jackpot!` |
| < 4 s | `SS   Sick Skills!` |
| < 7 s | `S    Savage!` |
| < 12 s | `A    Apocalyptic!` |
| < 20 s | `B    Badass!` |
| < 40 s | `C    Crazy!` |
| otherwise | `D    Dismal.` (first bakes download Minecraft) |

The rank has no other effect.

## 7. Running

### 7.1 Common

`pray` bakes (unless `--no-build`; then the jar MUST already exist), resolves the version (`--version`, default `minecraft`; a version outside `targets` gets a note, not an error), and prepares `run/<side>-<version>/`:

- `mods/` gets the project jar, after this mod's earlier builds are deleted: files named `<id>-<version>.jar` whose version starts with a digit (so a mod `holy` leaves `holy-hops-1.0.jar` alone);
- if the project depends on the library, `mods/miracle-toolchain.jar` is refreshed; otherwise it is removed.

Other jars in `mods/` are left alone: dropping more mods there is how to test combinations.

The game is started with the Java running the toolchain, in `run/<side>-<version>/`, with the loader first on the class path and `io.github.hronosin.miracle.MiracleMain` as the main class. Anything after `--` is appended to the game's arguments.

### 7.2 Server

- The EULA: `--eula` writes `eula.txt` with `eula=true`. Without it, and without an accepted `eula.txt` already there, `pray server` explains and exits 1. The toolchain MUST NOT accept Mojang's EULA on the user's behalf in any other way.
- On the first run, `server.properties` is written once: `server-ip=127.0.0.1`, `online-mode=false`, `spawn-protection=0`, and a motd. The author's later edits are kept.
- JVM: `-Xmx2G --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Dmiracle.target=net.minecraft.server.Main`; game argument `nogui`. The class path is the loader, the server jar and the libraries from Mojang's server bundler.

### 7.3 Client

- Libraries, natives for this OS and architecture, and assets are fetched as the version's metadata describes, honouring its OS rules.
- The player is **offline**: name from `--username` (default `Pilgrim`), UUID derived the way vanilla does for offline players (`OfflinePlayer:<name>`), access token `0`, user type `legacy`. No Microsoft account is involved.
- JVM and game arguments come from the version's metadata with its `${...}` variables substituted; the toolchain adds `-Xms1G -Xmx4G`, `-Dmiracle.target=<the version's main class>`, and `--enable-native-access=ALL-UNNAMED` and `-cp` if the metadata lacks them. Native libraries are used as jars on the class path.

### 7.4 Endings

After the game exits:

- exit code 0 and no crash report written during the run: `Amen.`, exit 0;
- otherwise: `YOU DIED`, the game's exit code, and the newest crash report written during the run (from `crash-reports/`, by modification time). `pray` exits with the game's code, or 1 if the game exited 0 after crashing (a dedicated server does).

## 8. The mod jar and the loader

The toolchain's output is only useful if the loader takes it. This section fixes that contract.

### 8.1 Jar layout

```
miracle.mod.toml                          (3.2) MUST be at the root
<classes and resources>                   the mod, in readable (unobfuscated) names
META-INF/miracle/bake.toml                (6.5) absent if never baked
META-INF/miracle/baked/<v>/...            translated classes for obfuscated version <v>
META-INF/miracle/baked/<v>/rgct-names.txt
META-INF/miracle/fallback/<v>/...         fallback classes, as compiled
```

A jar without `miracle.mod.toml` is skipped with a warning.

### 8.2 Discovery and order

1. Every `*.jar` directly in the mods folder is read (a missing mods folder is created empty). Two jars with the same id stop the game, as do a broken `miracle.mod.toml` or `bake.toml`, a bad id, a missing entrypoint without `library = true`, and a malformed `depends` entry.
2. Every `depends` entry is checked: the mod MUST be present, and at least the stated version; a mod MUST NOT depend on itself. `miracle` is the loader's own version. All problems are listed together, then the game stops.
3. Load order: dependencies before the mods that need them; otherwise alphabetical by id. A dependency cycle stops the game and names the circle.

### 8.3 Choosing a variant

The loader reads the game version from `version.json` in the game jar (`-Dmiracle.gameVersion` overrides), and considers the game obfuscated if it has a `version.json` but no `net/minecraft/world/entity/LivingEntity.class` (`-Dmiracle.obfuscated` overrides). Then, per mod:

| the mod... | the game is obfuscated | the game is not |
|---|---|---|
| was baked for this version | its variant goes in front of its own classes; `rgct-names.txt` feeds the report | its variant (fallbacks merged in) goes in front of its own classes |
| was baked, not for this version | the game stops: the mod needs a variant for exactly this version | a warning if the version isn't in `checked` (and is known) |
| was never baked | a warning: names it uses won't be found | loads as is |

### 8.4 Lifecycle

1. Discover, check dependencies, order (8.2), pick variants (8.3). Publish the mod list through the `Mods` API.
2. Create each mod's entrypoint (libraries have none) and call `transform(Rgct)` on each, in load order. Game classes MUST NOT be loaded here: any class loaded now can no longer be patched, and if a mod targets one, the game stops and names it.
3. Freeze RGCT. Print the report (who patches what, with what effects) and warnings about possible conflicts and missing target classes. Compare with `miracle.lock` (8.7). `Mods.launched()` becomes true.
4. Call `onLaunch()` on each mod, in load order.
5. Run the game's `main`. Classes are patched as they load.

### 8.5 `io.github.hronosin.miracle.api.Mods`

Available from phase 2 on.

| member | returns |
|---|---|
| `all()` | every mod, in load order: `Mod(id, name, version, authors, jar, library, depends, icon)`; `iconBytes()` reads the icon from the jar |
| `get(id)`, `of(id)`, `isLoaded(id)` | one mod (`of` throws if absent) |
| `game()` | `Game(version, obfuscated, client)`; `client` is false on a dedicated server |
| `launched()` | true from phase 3 on |
| `owner(Class)` | the mod whose jar (baked variants included) a class came from, or empty |

### 8.6 Patching on behalf of dependents

`rgct.onBehalfOf(id)` returns an `Rgct` view whose patches are registered in the name of mod `id`, so the report, the conflict checks and crash blame name that mod. It MUST only be allowed when mod `id` lists the caller's mod in its `depends`, and only before the freeze.

### 8.7 `miracle.lock`

After the freeze the loader writes down what every mod patches, one line per patch, and keeps it in `miracle.lock` next to the mods folder (`-Dmiracle.lockFile` elsewhere):

```
game 26.3 server
mod hallelujah 0.3.0
  net.minecraft.world.entity.LivingEntity#getJumpPower()F intercept@RETURN [modifies return]
  net.minecraft.server.MinecraftServer#tickServer(Ljava/util/function/BooleanSupplier;)V @RETURN [observes]
mod miracle-toolchain 0.3.0
  ...
```

- A line is `<class>#<method><descriptor> <where> [<effects>, priority n]`, effects as in the report (`observes`, `reads only`, `modifies return`, `sets args`, `cancels`, `cancels with a value`...); `<class> raw [whole class]` and `<class> rawBytes [whole class]` for raw patches; the same hook twice reads `x2`. On an obfuscated game, names come from the baked variants' `rgct-names.txt` where available. Lines are sorted, mods by id: the file diffs well in version control.
- Patches made through `onBehalfOf` (8.6) count for the dependent mod: a library's patches are listed under the mod that asked for them.
- Each start compares. No lock yet: pin it. A lock pinned on another game (version or side): pin afresh, saying so. Same patches: say so; if only versions changed, rewrite quietly. Different patches: print, per mod, `<id> <old> -> <new>` (or `(new)`, `(gone)`, `(same version, different patches: a setting?)`) and the `+`/`-` lines.
- `-Dmiracle.lock`: `update` (default) prints the difference and pins the new state; `strict` prints it and stops the game without touching the file (delete it, or start once with `update`, to accept); `off` does nothing. Another value stops the game.
- An unreadable lock is reported and replaced.

## 9. The library

### 9.1 What it is

`miracle-toolchain.jar` is a mod (id `miracle-toolchain`, `depends = ["miracle>=0.3.0"]`) with an entrypoint. Its package is `io.github.hronosin.miracle.toolchain`. It has no privileges a mod couldn't have: everything below is built on RGCT and the API in section 8.

| part | alias | covers |
|---|---|---|
| `Omens` | `Events` | things that happen (9.4) |
| `Blessings`, `Blessing` | `Tweaks` | well-known game values with merge rules (9.5) |
| `Sermons` | `ChatCommands` | commands (9.6) |
| `Commandments` | `Config` | settings files (9.7) |
| `Scripture` | `Resources` | the mod jar's `data/` and `assets/` (9.8) |
| `Proclamations` | `Notices` | telling players things (9.9) |
| `Creation`, `Relic`, `Being` | `Content` | new items, blocks and entities (9.11) |
| `Telepathy`, `Scroll` | `Networking` | messages between client and server (9.12) |
| `Gestures` | `Keybinds` | keys (9.13) |
| `Communion` | `Handshake` | comparing mods when a player joins (9.14) |

An alias is a subclass that adds nothing; `Events.playerJoined(...)` *is* `Omens.playerJoined(...)`.

### 9.2 When to call it

Handlers and changes MUST be added in `onLaunch()` or later, never in `transform()`.

The reason is class loading. A handler whose parameter is, say, a `ServerPlayer` loads `ServerPlayer` (and `Player`, `LivingEntity`, `Entity`) the moment the handler is created. During `transform()` that would put those classes beyond anyone's reach to patch (8.4). So the library does its patching up front (9.3), and mods only hand over their handlers later, when using game classes is safe.

Subscribing before launch (any `Omens` method, a change on a `Blessing` (`multiply`, `add`, `clamp`, `set`), `Sermons.preach`, `Scripture.reveal`, `Creation.item`/`block`/`entity`, a channel's `onServer`/`onClient`, `Gestures.key`) MUST fail with an error that says to use `onLaunch()`. Merely building a `Blessing` (`Blessings.jumpPower()`, `forPlayers()`, `when(...)`) doesn't. `Commandments`, `Proclamations`, `Telepathy.channel(...)`, `Communion.bothSides()`/`eitherSide()` and `Scroll`s involve no hooks and MAY be used any time (`Proclamations` and sending need a live game, of course).

A handler's *parameters* MUST NOT be client-only classes (`Minecraft`, `LocalPlayer`...): `onLaunch()` also runs on dedicated servers, where creating such a handler fails because the class doesn't exist. That's why `clientTick` takes a `Runnable`; reach for `Minecraft.getInstance()` inside.

### 9.3 The Prophecy

In its `transform()`, the library reads the classes of every mod whose `depends` includes `miracle-toolchain`, **without loading them**, and works out what each will use. Then it registers exactly those hooks, through `onBehalfOf` (8.6), in that mod's name.

What it reads: every `.class` entry of the mod jar outside `META-INF/`. What counts:

| call in the mod's bytecode (owner in the library package) | prepares |
|---|---|
| `Omens.<omen>` or `Events.<omen>` | the omen's hook (9.4) |
| `Blessings.<value>()` or `Tweaks.<value>()`, then within the same method a change on the returned `Blessing` (`multiply`, `add`, `clamp`, `set`) | one hook for that value and that kind of change |
| `Blessing.priority(n)` between the two, with `n` an integer constant | for `set`: the hook at priority `n` |
| a change whose value can't be traced within its method (the `Blessing` came from a field or a parameter) | that change for every value the mod names anywhere |
| `Sermons.preach` or `ChatCommands.preach` | the command-tree hook |
| `Scripture.reveal` or `Resources.reveal` | the pack hook |
| `Creation.item`/`block`/`entity` or `Content.item`/`block`/`entity` | the registry and creative-tab hooks (library's name), and the pack hook, already revealed (the mod's things need their assets); with `entity`, also the attribute, data-fixer and (clients) renderer hooks |
| any call on `Telepathy`, `Networking` or a `Telepathy.Channel` | the wire hooks (library's name) |
| `Gestures.key` or `Keybinds.key` | the key hooks (library's name, clients only) |

It logs one line per mod: `MiracleToolChain foresees for <id>: <what>`, and a warning for every `priority(...)` whose argument isn't a constant. A mod foreseen to create things or use Telepathy is *bound* to both sides (9.14).

A call written in the mod counts whether or not it ever runs; that costs a hook that never fires, nothing more. A call the Prophecy can't see (made by reflection, from generated classes, or from another jar that doesn't itself depend on the library) finds no hook prepared, and MUST fail with an error saying so. Parts no mod uses MUST NOT patch anything. (The one exception is the library's own `/smite`, 9.10, which has its own switch.)

Creation, Telepathy, Gestures and Communion are shared machinery: one registry step, one wire, one keyboard, one handshake, installed once in the library's own name when any dependent uses them (Communion: always, unless switched off). What each mod adds is still checked against what the Prophecy foresaw for that mod.

At runtime, each hook loops over the handlers its mod added, in the order they were added. The mod calling is found from the call stack (the first class outside the library package) through `Mods.owner`.

### 9.4 Omens

| omen | handler | fires | game method (hook) |
|---|---|---|---|
| `serverStarted` | `Consumer<MinecraftServer>` | the world is loaded, before the first tick | `MinecraftServer#loadLevel()V` (return) |
| `serverStopping` | `Consumer<MinecraftServer>` | shutdown begins; the world is still there | `MinecraftServer#stopServer()V` (head) |
| `serverTick` | `Consumer<MinecraftServer>` | after every server tick | `MinecraftServer#tickServer(BooleanSupplier)V` (return) |
| `playerJoined` | `Consumer<ServerPlayer>` | a player is placed in the world | `PlayerList#placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)V` (return) |
| `playerLeft` | `Consumer<ServerPlayer>` | a player is about to be removed | `PlayerList#remove(ServerPlayer)V` (head) |
| `playerJumped` | `Consumer<ServerPlayer>` | a player jumps (server side) | `ServerPlayer#jumpFromGround()V` (head) |
| `chat` | `ChatJudge (player, message) → Verdict` | a chat message is about to be broadcast; `message` is its signed text | `ServerGamePacketListenerImpl#broadcastChatMessage(PlayerChatMessage)V` (head) |
| `blockBroken` | `BlockJudge (player, pos) → Verdict` | a player is about to break a block | `ServerPlayerGameMode#destroyBlock(BlockPos)Z` (head) |
| `entityHurt` | `HurtJudge (victim, source, amount) → Verdict` | a living entity is about to take damage; `amount` is before armor and before any mod's changes | `LivingEntity#hurtServer(ServerLevel, DamageSource, F)Z` (head) |
| `entityDied` | `BiConsumer<LivingEntity, DamageSource>` | a living entity dies, players included | `LivingEntity#die(DamageSource)V` and `ServerPlayer#die(DamageSource)V` (head) |
| `clientTick` | `Runnable` | after every client tick; never on a dedicated server (no hook is installed there) | `Minecraft#tick()V` (return) |

**Verdicts.** `SPARE` (alias `ALLOW`) lets it happen; `SMITE` (alias `CANCEL`) stops it: the chat message isn't sent; the block stays (`destroyBlock` returns false and the game resends the block to the player); the hit doesn't land (`hurtServer` returns false: no damage, no knockback). Within one mod, the first `SMITE` ends that mod's judging for the event. Across mods, RGCT's rule applies: any cancel wins.

Commands (`/...`) are not chat. Server omens fire on the dedicated server and the integrated one alike, on the server thread.

### 9.5 Blessings

```java
Blessings.<value>()                 // Blessing<LivingEntity>
    [.forPlayers() | .forType(Class)]   // narrow to a subtype
    [.when(predicate)]...               // more conditions, all must hold
    [.priority(n)]                      // only for set
    .multiply(x) | .add(x) | .clamp(min, max) | .set(x)
```

For `multiply` and `add`, `x` is a number, or a `ToDoubleFunction` of the entity evaluated each time the value is computed; `set` and `clamp` take numbers. Narrowing returns a new `Blessing`; each change takes effect from the call on, and lasts.

| value | what | type | game method, slot |
|---|---|---|---|
| `jumpPower` | how hard a living entity pushes off the ground | float | `LivingEntity#getJumpPower()F`, return |
| `movementSpeed` | walking speed | float | `LivingEntity#getSpeed()F` and `Player#getSpeed()F` (players compute it separately), return |
| `fallDamage` | fall damage after vanilla's own math | int | `LivingEntity#calculateFallDamage(DF)I`, return |
| `damageTaken` | incoming damage, before armor | float | `LivingEntity#hurtServer(ServerLevel, DamageSource, F)Z`, argument 3 |

Jump power and speed of a player's own movement are computed on that player's client; a mod changing them for players belongs on the client too.

**Merging.** Every mod's changes to a value combine, in any load order:

```
value = clamp( (base + Σ adds) × Π factors )      base = vanilla's value, or the winning set
```

- `set`: the highest priority wins; two mods setting different values at the same priority are a conflict, reported with both mods' names. Within one mod, the last applicable `set` at a priority wins.
- `clamp`: all ranges apply (their intersection); ranges that don't overlap are a conflict.
- Integer values are rounded to the nearest integer at the end; `set` on an integer value rounds its argument.
- `forType` with a class that can never be the value's owner, and `clamp` with `min > max`, MUST fail immediately.

### 9.6 Sermons

- `Sermons.preach(Consumer<CommandDispatcher<CommandSourceStack>>)` and `Sermons.preach(Pulpit (dispatcher, buildContext))` register code that adds commands. It runs for **every** command tree the game builds (at start, on `/reload`, per world in singleplayer), right after vanilla's own commands are in. Hook: `Commands#<init>(CommandSelection, CommandBuildContext)V` (return).
- `Sermons.reply(source, text)` sends a white reply to whoever ran the command (not broadcast to operators); `Sermons.rebuke(source, text)` a red one.
- Commands are plain Brigadier; permissions, arguments and suggestions work as in vanilla. Clients need nothing installed.

### 9.7 Commandments

`Commandments.mine()` (the calling mod's; any time), `Commandments.of(rgct)` (in `transform()`), `Commandments.of(name)`: the settings in `<configDir>/<name>.toml` (`configDir` from 4.2; `name` matching `[A-Za-z0-9_.-]+`).

| getter | returns |
|---|---|
| `number(key, default, comment)` | double; integers are accepted |
| `integer(key, default, comment)` | long; a decimal is rounded, with a warning |
| `flag(key, default, comment)` | boolean |
| `text(key, default, comment)` | string |
| `list(key, default, comment)` | list of strings |

Rules:

- The file is read once, when the object is made; `reload()` reads it again.
- A key missing from the file is **appended** with its comment (`# ...` lines) and its default; a new file starts with a two-line header. Nothing else in the file is ever rewritten: the player's values, comments and order stay.
- A value of the wrong type is reported (`Thou shalt not`) and the default is used for this run. The file isn't touched.
- A file that doesn't parse is reported (each time it is read); every setting uses its default, and the file MUST NOT be written to while it's broken.
- Keys match `[A-Za-z0-9_-]+`; anything else is a programming error and throws.

### 9.8 Scripture

`Scripture.reveal()` adds the mod jar's `data/` and `assets/` to the game's built-in data pack and resource pack.

- The namespaces exposed are the folder names under `data/` and `assets/` in the jar, found at startup, `minecraft` excluded. A jar with none gets a warning and nothing is added.
- Hook: `VanillaPackResourcesBuilder#build(PackLocationInfo)` (head), for every built-in pack the game assembles; data packs read only `data/`, resource packs only `assets/`. The jar is opened as a zip file system the first time, and stays open.
- Files are always on; there's no pack to enable. Mods SHOULD use their own namespace; overriding vanilla's files this way is not supported.

### 9.9 Proclamations

No hooks; each call sends packets whose shape hasn't changed across the supported versions.

| call | sends |
|---|---|
| `overlay(player, text)` | a line above the hotbar (`ClientboundSetActionBarTextPacket`) |
| `title(player, title, subtitle[, fadeIn, stay, fadeOut])` | timing, the optional subtitle, then the title; default timing 10, 60, 20 ticks |
| `broadcast(server, text)` | a system chat line to every player |

`overlay` and `broadcast` accept a `String` or a `Component`; `title` takes `Component`s, and the subtitle MAY be null.

### 9.10 `/smite`

The library's own command, and its only useless feature:

- `/smite [reason]`, owner permission level (`Commands.LEVEL_OWNERS`) only;
- replies `So be it.`, then on the next server tick throws `Smitten by MiracleToolChain: <reason>` (default reason: "thou hast asked for it"). The game handles it like any crash: a crash report in `crash-reports/`, then its normal shutdown;
- controlled by `smite` (default `true`) in `config/miracle-toolchain.toml`, read at startup. When `false`, neither the command nor its tick hook exists.

### 9.11 Creation

```java
Relic<Item>  water = Creation.item("holy_water", p -> new Item(p.stacksTo(16))).inTab("food_and_drinks");
Relic<Block> altar = Creation.block("altar", p -> new Block(p.strength(2f))).inTab("functional_blocks");
```

- `item(name[, factory])` and `block(name[, factory])` MUST be called in `onLaunch()` (9.2). Names match `[a-z0-9_./-]+`; the id is `<namespace>:<name>`, the namespace being the mod id with `-` as `_`. The same id twice MUST fail. Called once the registries are built, they MUST fail too ("frozen").
- The factory receives properties that already carry the id (`Item.Properties.setId`, `BlockBehaviour.Properties.setId`). Defaults: `new Item(p)`; `new Block(p.strength(1f))`.
- `block(...)` also makes an item that places the block (`BlockItem`, same id, named from the block), reachable as `relic.item()`, and links it so `block.asItem()` finds it.
- `inTab(id)` puts the item (for a block, its item) in a creative tab: `building_blocks`, `colored_blocks`, `natural_blocks`, `functional_blocks`, `redstone_blocks`, `tools_and_utilities`, `combat`, `food_and_drinks`, `ingredients`, `spawn_eggs`, or any tab's full id. It shows there and in search.
- `Relic.get()` is the thing itself; before the registries are built it throws. `exists()`, `id()`.

Mechanics:

| step | hook |
|---|---|
| blocks, in call order, then items, registered after vanilla's and before the registries freeze; each new block state gets the next network id, in the same order on every side | `BuiltInRegistries#freeze()V` (head) |
| items added to their tabs whenever the game fills a tab | `CreativeModeTab#buildContents(ItemDisplayParameters)V` (return) |

**Entities.**

```java
Being<Heretic> heretic = Creation.entity("heretic",
        () -> EntityType.Builder.of(Heretic::new, MobCategory.MONSTER).sized(0.6f, 1.95f))
    .attributes(() -> Zombie.createAttributes())
    .looksLike("zombie")
    .spawnEgg();
Being<ThrownHolyWater> thrown = Creation.entity("thrown_holy_water",
        () -> EntityType.Builder.<ThrownHolyWater>of(ThrownHolyWater::new, MobCategory.MISC).sized(0.25f, 0.25f))
    .looksLikeItem();
```

- `entity(name, builder)` takes a *supplier* of the builder, called when the registries are built: the builder touches game classes that mustn't be touched in `onLaunch()`. Names and ids as for items; the same entity id twice MUST fail.
- `Being` is to entity types what `Relic` is to items: `get()` (the `EntityType`, throws before the registries are built), `exists()`, `id()`. Its setters MUST be called before the registries are built:
  - `attributes(supplier)`: the living entity's attributes, built the first time the game asks (attributes can't be read until the registries are frozen). A living entity without them can't be made; the game says so.
  - `spawnEgg()`: an item `<name>_spawn_egg` (a `SpawnEggItem` carrying the type), in `spawn_eggs`; `egg()` is its `Relic`.
  - Looks, by name so a dedicated server never loads a renderer: `looksLike("zombie")` borrows a vanilla entity's renderer (the entity SHOULD extend that entity's class, whose fields the renderer reads); `looksLikeItem()` draws the carried item (`ThrownItemRenderer`, for `ItemSupplier` entities such as `ThrowableItemProjectile`s); `renderedBy("com.example.MyRenderer")` makes the mod's own `EntityRenderer`, which needs a public constructor taking `EntityRendererProvider.Context`. With none, or a vanilla name the game can't draw, the entity is invisible and the log says so.
- Names: `entity.<ns>.<name>` in the lang file. Drops: `data/<ns>/loot_table/entities/<name>.json`, none without one.

Mechanics:

| step | hook |
|---|---|
| blocks, in call order, then entity types, then items (spawn eggs need their type), registered after vanilla's and before the registries freeze; each new block state gets the next network id, in the same order on every side | `BuiltInRegistries#freeze()V` (head) |
| items added to their tabs whenever the game fills a tab | `CreativeModeTab#buildContents(ItemDisplayParameters)V` (return) |
| our types' attributes answer before vanilla's map | `DefaultAttributes#getSupplier(EntityType)` and `#hasSupplier(EntityType)` (head) |
| our types need no save-data fixer, and the builder doesn't log an error about it | `Util#fetchChoiceType(TypeReference, String)` (head, for our ids) |
| renderers join the game's providers before anyone reads them (clients) | `EntityRenderers#createEntityRenderers(Context)` and `#validateRegistrations()` (head) |

Blocks, items, entity types and block states travel as numbers, so client and server MUST have the same mods creating the same things in the same order; Communion (9.14) checks it at the door.

Everything besides code (models, textures, names, loot tables) comes from the jar's `resources/`: a mod that creates things has them revealed automatically (9.3). `miracle scribe` writes a starting set (5.2).

### 9.12 Telepathy

```java
static final Telepathy.Channel PRAYER = Telepathy.channel("hallelujah:prayer");
PRAYER.onServer((player, scroll) -> ...);     // server thread
PRAYER.onClient(scroll -> ...);               // client thread
PRAYER.toServer(scroll);  PRAYER.toPlayer(player, scroll);  PRAYER.toEveryone(server, scroll);
```

**Channels.** A name `namespace:path` (lowercase). The same name is the same channel, on both sides; two names whose hashes collide MUST fail at `channel(...)`. `toServer` returns false where there's no server to send to (title screen, dedicated server).

**Scrolls.** A message is a `Scroll`: values written in order (`writeInt`, `writeLong`, `writeDouble`, `writeBoolean`, `writeString`, `writeBytes`), read back in the same order. Each value carries a type tag; reading the wrong type, past the end, or a length that lies MUST fail with a message naming what was found. `toString()` shows the contents (`[string "amen", int 3]`). A received scroll is read-only; a new one is write-only.

**Wire.** Everything travels in one custom payload, `miracle:telepathy`, whose body is a batch:

```
batch   = 'M' 'T' version:u8(1) seq:varint count:varint message*
message = channel:i32 (FNV-1a of the name) length:varint scroll
```

- Each side queues what it sends during a tick and flushes it once, at the end of its tick (server: `MinecraftServer#tickServer` return; client: `Minecraft#tick` return), as one batch, or as several if the tick's messages exceed the limit: 32,000 bytes client to server, 1,000,000 server to client (the game's own limits are 32 KiB and 1 MiB). A single scroll over the limit MUST fail at send time.
- `seq` counts batches per connection and direction, from 1. The client starts again at 1 on every new connection.
- Channel names never cross the wire. Nothing received is ever executed: a message only reaches a handler the receiving side registered for that channel. Messages for channels a client doesn't know are ignored (a mod it doesn't have).
- Hooks: `CustomPacketPayload$1#findCodec(Identifier)` (head: answers with our codec for our id, both directions; other ids go on to vanilla's), `ServerGamePacketListenerImpl#handleCustomPayload(ServerboundCustomPayloadPacket)` (head: hands the batch to the server thread), `ClientPacketListener#handleCustomPayload(CustomPacketPayload)` (head: handles ours and stops there).
- A handler that throws is logged with its channel; the connection and the other handlers carry on.

**The Inquisition.** On the server, per connection, each batch from a client is examined before any handler sees it:

| heresy | when |
|---|---|
| out of order | its `seq` isn't the last honest one plus 1 (replayed, reordered, skipped, forged). Only an honest batch moves the count, so a forged number can't make the real next one look late. |
| flood | more batches in one server tick than `inquisition_batches_per_tick` (default 4) |
| unknown channel | a message for a channel the server doesn't know |
| malformed | anything that doesn't parse as exactly one batch, or larger than the client's limit |

Each heresy is logged (`Inquisition: <player> <what>. Penance: <action>.`), then `inquisition` in `config/miracle-toolchain.toml` decides: `log` (carry on, where possible), `drop` (default: the batch is discarded), `kick` (discarded, and the player disconnected). This catches packets made by tools that don't speak the protocol; a client built on this very code sends honest-looking lies, so servers MUST still validate what handlers are told.

### 9.13 Gestures

```java
Gesture pray = Gestures.key("pray", "G", () -> PRAYER.toServer(new Scroll()));
```

- `key(name, defaultKey, action)` MUST be called in `onLaunch()`; after the game has read its key settings it MUST fail. Names match `[a-z0-9_.-]+`.
- Keys are named, not numbered (key numbers changed meaning when the game moved from GLFW to SDL in 26.x; the names in `options.txt` didn't): a letter or digit, `F1`–`F24`, `KP_0`–`KP_9`, `SPACE`, `ENTER`, `TAB`, `BACKSPACE`, `INSERT`, `DELETE`, `HOME`, `END`, `PAGE_UP`, `PAGE_DOWN`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `CAPS_LOCK`, `LEFT_SHIFT`, `LEFT_CONTROL`, `LEFT_ALT`, `RIGHT_SHIFT`, `RIGHT_CONTROL`, `RIGHT_ALT`, punctuation (`MINUS`, `EQUAL`, `COMMA`, `PERIOD`, `SLASH`, `SEMICOLON`, `APOSTROPHE`, `LEFT_BRACKET`, `RIGHT_BRACKET`, `BACKSLASH`, `GRAVE`), `NONE` for unbound, or the game's own name (`key.keyboard.g`, `key.mouse.middle`). Case and `_`/space don't matter. An unknown name MUST fail.
- The key appears in Options → Controls, rebindable, as `key.<ns>.<name>` in a category `key.category.<ns>.keys`, and is saved in `options.txt` like vanilla's. Their display names come from the mod's `lang` files.
- The action runs on the client thread once per press, while no screen is open. `Gesture.isDown()` tells whether it's held. On a dedicated server everything here does nothing.
- Hooks (clients only): `Options#load()V` (head: adds the gestures to the options' full key list, the longest `KeyMapping[]` it has, before `options.txt` is read), `Minecraft#tick()V` (return: presses become actions).

### 9.14 Communion

When a player joins, before they are in the world, the server and the client compare their mods; a mismatch ends with a list of what's wrong on the player's disconnect screen, instead of a crash on the first unknown block.

**Bound mods.** A mod MUST be on both sides, in the same version, when it is *bound*:

- the Prophecy foresaw it creating things or using Telepathy (9.3); a mod that creates things stays bound whatever it says;
- it called `Communion.bothSides()` (a server mod whose client half matters);
- not if it called `Communion.eitherSide()` (Telepathy that copes with silence), unless it creates things;
- the library itself is bound whenever another mod is.

Everything else (omens, blessings, commands, keys) is one side's business.

**The manifest** (payload `miracle:communion`, both directions): `Scroll` values `int 0x4D434D31`, `string` library version, `int n`, then per mod `string id`, `string version`, `boolean bound`, then `long` creation fingerprint (FNV-1a, 64 bits, over `block|entity|item <id>` lines in registration order) and `int` creation count.

**The judgement**, the same on both sides, in the player's words:

| finding | line |
|---|---|
| a server-bound mod the client lacks | `Missing: <id> <version>` |
| a bound mod in another version | `Different version: <id> (yours <v>, the server's <v>)` |
| a client-bound mod the server lacks | `The server doesn't have: <id> <version> (remove it to join)` |
| none of those, but different creations | `Same mods, different creations: ...` |

A refusal reads `Communion refused. Your mods and the server's don't match:`, the lines, and `No miracle today.`

**The rite.**

1. Server: a configuration task (`miracle:communion`) added after the game's optional ones (hook: `ServerConfigurationPacketListenerImpl#addOptionalTasks()V`, return) sends the server's manifest. If the server has no bound mods, the task ends at once: any client may join, vanilla included.
2. Client (hook: `ClientConfigurationPacketListenerImpl#handleCustomPayload(CustomPacketPayload)V`, head): answers with its own manifest, judges, and on findings disconnects itself with the refusal.
3. Server (hook: `ServerCommonPacketListenerImpl#handleCustomPayload(ServerboundCustomPayloadPacket)V`, head): logs the answer's verdict when it arrives (`Communion: <player> shares our faith (n mod(s))`, or `... refused: <findings>`), and the task disconnects a refused player with the same refusal. With bound mods and no answer within `communion_timeout` seconds (default 10), it disconnects with the list of mods to install (a vanilla client, or one without the library). An answer that doesn't parse is refused.
4. Client (hook: `ClientConfigurationPacketListenerImpl#handleConfigurationFinished(...)V`, head): a connection that finishes configuration without ever being offered communion, while the client has bound mods, is left, with `This server doesn't offer communion (no MiracleToolChain there, or it's switched off), but these mods of yours need the same on both sides:` and the list.

`Communion.modsOf(player)` returns what a player's game said it has (id to version), empty for a game that never answered; `Communion.has(player, id)` asks about one mod. `communion` (default `true`) in `config/miracle-toolchain.toml` switches all of it off; nothing is hooked then.

## 10. Templates

`miracle genesis <name> --template <t>` writes one of these as the mod's class. Each MUST compile against every supported unobfuscated version and bake for every supported obfuscated one without fallbacks. In the template sources `__PACKAGE__`, `__CLASS__` and `__ID__` are replaced with the project's package, class and id.

| template | after | behavior | commands | settings (`config/<id>.toml`) | other files |
|---|---|---|---|---|---|
| `aura` | RWBY | each player's Aura soaks up hits smaller than what's left of it (the hit is cancelled and the Aura drained); a hit as big or bigger breaks it and lands, and the Aura regenerates once the player has been calm long enough | `/aura` | `max_aura` 20, `regen_per_second` 1, `calm_seconds` 5 | |
| `stylish` | Devil May Cry | a style meter from D to SSS: damage dealt earns points (double against a different kind of mob than last time), kills a bonus; taking a hit drops a rank; points drain every second; the rank shows above the hotbar when it changes | `/style` | `decay_per_second` 4, `kill_bonus` 15 | |
| `zandatsu` | Metal Gear Rising | killing a non-player while sneaking restores full health; a blow that would kill a player is cancelled, leaving 2 hearts, once per cooldown ("Nanomachines, son.") | `/nanomachines` | `nanomachine_cooldown_seconds` 300, `zandatsu_heals` true | |
| `you-died` | Dark Souls | `YOU DIED` title with the player's death count; `VICTORY ACHIEVED` for everyone when a player kills the Wither, the Ender Dragon, the Warden or an Elder Guardian; a "Praise the Sun" greeting | `/deaths` | `praise_the_sun` true | `config/<id>-deaths.txt`: `name=count` per line |
| `grace` | Elden Ring | a greeting title on joining; messages left on the ground, shown above the hotbar to players within reach; `GREAT ENEMY FELLED` for the same bosses | `/message <text>`, `/appraise` | `greeting` "Rise, Tarnished.", `message_reach` 3 | `config/<id>-messages.txt`: `dimension\|x\|y\|z\|appraisals\|author\|text` per line |

State other than these files is kept in memory and resets when the server restarts.

## 11. Supported game versions

| version | obfuscated | status |
|---|---|---|
| 26.3 | no | primary; everything in section 9 checked; client and dedicated server run, client also headless in tests, multiplayer included |
| 26.2 | no | checked; client and dedicated server run |
| 26.1, 26.1.1, 26.1.2 | no | checked (every library and example reference present) |
| 1.21.11 | yes | baked; client (through Prism, and headless in tests, multiplayer included) and dedicated server run |

Every game method named in section 9 has the same name and descriptor in all of them. A new version is supported once the library bakes (or checks) against it without holes; if a future version renames something, the library gets a fallback for it (6.4), and mods using the library need not change.

"Run" above means started with the loader and mods and exercised. "Headless" means the real client with software OpenGL and no window (26.x: SDL's offscreen driver; 1.21.11: Xvfb), started straight into a world with `--quickPlaySingleplayer` or onto a local server with `--quickPlayMultiplayer`, a test mod pressing keys, throwing things and forging packets from inside: new blocks load in chunks, items and spawn eggs show in their tabs with their names, entities spawn, save, take hits and are drawn by the renderers they asked for, keys save and fire, messages go both ways, forged batches are caught, and Communion lets matching games in and turns away the rest (a missing mod, an extra one, no library at all, a server without it) with the right lines. What needs eyes (textures, titles, meters) still wants a person.

## 12. Versioning and stability

- The loader, the toolchain and the library share one version number, `MAJOR.MINOR.PATCH`. While `MAJOR` is 0, a minor release MAY change anything, and says what in its notes.
- A mod states what it needs with `depends` (`"miracle>=0.3.0"`, `"miracle-toolchain>=0.3.0"`).
- Stable within 0.x, unless a release note says otherwise: the file formats in sections 3.2, 3.3, 6.5 and 8.1; command names and aliases; the solemn and boring names in Appendix B.
- Output wording is not an interface, apart from these markers, which scripts MAY rely on: `HERESY:`, `Baked:`, `Amen.`, `YOU DIED`, `BONFIRE LIT` (from `bonfire`/`backup`), and `[ok]`/`[!!]` in `confess`.

## 13. Known limits

- The library has no block entities or menus (screens) yet; new entities look like a vanilla one, like their item, or bring their own renderer class, but the library offers no models or textures for them.
- New entities don't spawn by themselves in the world; spawn eggs and commands only.
- Communion asks bound mods for exactly the same version; there is no way yet to declare a range of compatible versions.
- Mappings are Mojang's only; no Yarn.
- `miracle bake` does not fail on holes (6.5); read its report.
- The Prophecy sees only calls written in the dependent mod's own classes (9.3); `Blessing.priority` needs a constant.
- String references to game names outside RGCT targets (reflection) are never translated (6.3).
- Fallbacks compile against the primary version's libraries, not the target's.
- `bonfire` doesn't check whether the game is running.
- `miracle bake` compiles fallbacks for any `fallback/<v>/` folder, target or not.
- MiracleLoader needs Java 25, so the oldest reachable versions are those that run on it.

---

## Appendix A: exit codes

| code | when |
|---|---|
| 0 | success; also `messages`, `exorcise`, the rituals that don't say otherwise |
| 1 | a heresy; a failed download or disk operation; `confess` with sins; `heresy` with finds; `forge`; `zandatsu` on a non-mod; `bonfire rest` with nothing to rest at; `pray` when the game crashed but exited 0 |
| 2 | `miracle-bake` usage error |
| 130 | interrupted |
| other | `pray`: the game's own exit code |

## Appendix B: names

| solemn | boring | where |
|---|---|---|
| `genesis` | `new` | command |
| `bake` | `build` | command |
| `pray` | `run` | command |
| `confess` | `doctor` | command |
| `bonfire` / `grace` | `backup` | command |
| `messages` | `todo` | command |
| `zandatsu` | `inspect` | command |
| `exorcise` | `clean` | command |
| `Omens` | `Events` | library |
| `Blessings` | `Tweaks` | library |
| `Sermons` | `ChatCommands` | library |
| `Commandments` | `Config` | library |
| `Scripture` | `Resources` | library |
| `Proclamations` | `Notices` | library |
| `Creation` | `Content` | library |
| `Telepathy` | `Networking` | library |
| `Gestures` | `Keybinds` | library |
| `Communion` | `Handshake` | library |
| `Being` | entity type | library (9.11) |
| `scribe` | `assets` | command |
| `dictionary` | `mappings` | command |
| the Inquisition | packet tripwire | library (9.12) |
| `Verdict.SPARE` / `SMITE` | `ALLOW` / `CANCEL` | library |
| heresy | user error | everywhere |
| the Prophecy | static usage analysis | library (9.3) |
