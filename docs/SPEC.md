# MiracleToolChain Specification

> Everything you need, and several things you don't. Specified.

| | |
|---|---|
| Version | 1.4.1 |
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
| `miracle-bake.jar` | `miracle-bake` alone, for scripts and the build | the JDK |
| `miracle-toolchain.jar` | the library: an ordinary mod (id `miracle-toolchain`) | MiracleLoader ≥ 1.0.0, and the game |
| `miracle`, `miracle.cmd` | wrappers that run `miracle.jar` next to them (the release zip) or `build/miracle.jar` (a checkout, building it first if it's missing); `miracle.cmd` also checks that the Java is 25 or newer | bash; cmd (Windows) |

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
| `entangles` | string array | no | since 1.1.0. The same form as `depends`, but soft: a mod listed here may be missing. If it's there, in a version that's new enough, it loads first and the two are *entangled* (`Mods.entangled`, 8.5); see 8.2. For optional links to other mods (Event Horizon's `Wormhole`, 9.15). |

A mod that uses the library MUST list `"miracle-toolchain"` in `depends` (with or without a version). That is how the toolchain knows to compile against the library and ship it to `run/`, and how the library knows to prepare the mod's hooks (9.3).

### 3.3 `miracle.project.toml`

| key | type | required | meaning |
|---|---|---|---|
| `minecraft` | string | yes | the version the sources are written and compiled against. MUST be unobfuscated (26.1 or newer); an obfuscated one is rejected with a pointer to `targets`. |
| `targets` | string array | no | versions `bake` checks the mod against; default `[minecraft]`. Obfuscated targets get a baked variant (6.3). `minecraft` need not be listed; it is always checked. Entries are versions or patterns (below). |
| `modrinth` | string | no | the Modrinth project (slug or id) `ascend modrinth` publishes to (5.1) |
| `modrinth_loaders` | string array | no | loader tags for Modrinth versions; default `["miracle"]` |
| `modrinth_requires` | string array | no | Modrinth projects (slugs or ids) every version requires |
| `github` | string | no | the repository (`owner/name`) `ascend github` makes releases in |
| `against` | string array | no | since 1.1.0. Other mods' jars (paths relative to the project) to compile against, for code that talks to mods this one `entangles`: on the class path of `bake` (fallbacks and the baker included) and of `scriptorium`'s projects, never packed into this mod's jar. `pray` copies those that are MiracleLoader mods into `run/<side>-<version>/mods/`. A path that doesn't exist is a heresy. |

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
| `MODRINTH_TOKEN`; `GITHUB_TOKEN`, `GH_TOKEN` | env | toolchain | `ascend`'s credentials (5.1); never printed |
| `MIRACLE_MODRINTH_API`, `MIRACLE_GITHUB_API` | env | toolchain | where `ascend` sends its requests (default `https://api.modrinth.com/v2`, `https://api.github.com`) |
| `-Dmiracle.showCommand=true` | property | toolchain | `pray` prints the full game command line |
| `MIRACLE_YUKARI=0`, `-Dmiracle.yukari=false` | env, property | toolchain | no remarks from Yukari after errors (7.4) |
| `PRISM_DATA` | env | `consecrate`, the build | Prism Launcher's data folder, when it isn't where Prism keeps it by default (5.2) |

Toolchain properties (`-D...`) go to the JVM running `miracle.jar`; through the `miracle` wrapper, pass them in `JDK_JAVA_OPTIONS`, or use the environment variables.
| `JAVA_HOME` | env | the wrappers (`miracle`, `build.sh`, and their `.cmd` twins) | which Java runs the toolchain; `pray` starts the game with the same Java |
| `DISPLAY`, `WAYLAND_DISPLAY` | env | `confess` | whether `pray client` has somewhere to open a window |
| `-Dmiracle.target` | property | loader | the game's real main class (default `net.minecraft.client.main.Main`) |
| `-Dmiracle.modsDir` | property | loader | mods folder (default `mods`) |
| `-Dmiracle.gameClasspath` | property | loader | game jars, instead of the JVM class path |
| `-Dmiracle.dump` | property | loader | folder to write every patched class into |
| `MIRACLE_JAVA`, `-Dmiracle.java` | env, property | `Resurrection` | the Java 25+ to relaunch in: a Java home or a `java` binary (8.8) |
| `-Dmiracle.javaSearch=explicit` | property | `Resurrection` | look only at `miracle.java`/`MIRACLE_JAVA` and `JAVA_HOME` (8.8) |
| `-Dmiracle.showCommand=true` | property | `Resurrection` | print the relaunch command (8.8) |
| `-Dmiracle.resurrected` | property | `Resurrection` | set on the relaunched game (to the old Java's version); a relaunch that is still too old stops instead of looping |
| `-Dmiracle.directCalls=false` | property | loader | patch the old way: every patched spot calls the dispatcher with an id, instead of an `invokedynamic` site bound to its hook |
| `-Dmiracle.gameVersion`, `-Dmiracle.obfuscated` | property | loader | override version detection (8.3) |
| `-Dmiracle.configDir` | property | library, templates | config folder (default `config`, relative to the game folder) |
| `-Dmiracle.lock` | property | loader | `update` (default), `strict` or `off` (8.7) |
| `-Dmiracle.lockFile` | property | loader | where `miracle.lock` lives (default: next to the mods folder) |
| `-Dmiracle.rawGraphics` | property | loader | since 1.2.0: `warn` (default), `refuse` or `allow`, for mods that call OpenGL or Vulkan directly (8.10) |
| `-Dmiracle.backend` | property | loader | since 1.3.0: `auto` (default: the game's own `options.txt`), `opengl` or `vulkan`: which graphics backend the game is set to use (8.10) |

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

Files created: `miracle.mod.toml` (version `0.1.0`, `authors` = the OS user name, and `depends = ["miracle-toolchain>=1.0.0"]` unless `--ascetic`), `miracle.project.toml` (with comments, the `ascend` keys commented out), `src/<package>/<Class>.java`, an empty `resources/`, `fallback/README.md`, `.gitignore` (`build/`, `run/` and the IDE files of `scriptorium`) and `README.md`.

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

#### `ascend modrinth|github` (alias `publish`)

```
miracle ascend modrinth [--project <slug>] [-m <text> | --notes <file>] [--type release|beta|alpha] [--no-build] [--dry-run]
miracle ascend github [--repo <owner/name>] [--tag <tag>] [--draft] [-m <text> | --notes <file>] [--type ...] [--no-build] [--dry-run]
```

Bakes (unless `--no-build`; then the jar MUST exist), then publishes `build/<id>-<version>.jar` as a new version.

- **Game versions** are exactly `minecraft` plus the jar's `bake.toml` `checked` and `baked` lists (6.5), newest first: what the bake vouches for, nothing more.
- **Changelog**: `-m`, or the file `--notes` names (relative to the project), or `<name> <version>.`. Both is a heresy.
- **Type**: `--type`, or from the version: `alpha`/`snapshot` in it make an alpha, `beta`/`-rc`/`pre` a beta, anything else a release.
- **`--dry-run`** prints what would be sent and sends nothing; no token is needed.
- **Tokens** come from the environment only and MUST NOT be printed or sent anywhere but their own site: `MODRINTH_TOKEN` (a personal access token that may create versions); `GITHUB_TOKEN`, `GH_TOKEN`, or the output of `gh auth token`.

**Modrinth.** The project comes from `--project` or `modrinth` (3.3), and MUST already exist. Before uploading, the toolchain asks Modrinth for its loader tags; a loader it doesn't list is a heresy that names the loaders it does. (MiracleLoader is not on Modrinth's list as of this version; until it is, `ascend github` is the way.) Game versions Modrinth doesn't list are left out with a note. Then `POST /v2/version` (multipart: `data` JSON with `name` `<name> <version>`, `version_number`, `changelog`, `dependencies` (each of `modrinth_requires`, `required`), `game_versions`, `version_type`, `loaders`, `featured` true, `status` `listed`, `project_id`, `file_parts` `["file"]`, `primary_file` `file`; and the jar as part `file`). Success prints the version's page.

**GitHub.** The repository comes from `--repo` or `github`. `POST /repos/<repo>/releases` with tag `--tag` or `v<version>` (GitHub creates the tag on the default branch if it doesn't exist), name `<name> <version>`, the changelog plus a line naming the checked versions, `prerelease` for anything but a release, `draft` with `--draft`. Then the jar is uploaded as an asset. A release that already exists for the tag is a heresy; so is an upload that fails, with a link to the release to attach it by hand.

`MIRACLE_MODRINTH_API` and `MIRACLE_GITHUB_API` point either at another address (Modrinth's staging server, a test double).

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

Opens a mod jar (default: the project's last bake) without running anything and prints: name, id and version; entrypoint ("spine"); `depends`; class count; the game classes (simple names) it names in `rgct.target(...)` calls with a constant string; the library API it calls (public parts only); whether it brings OSHI hooks (`raw`, `rawBytes`); the versions it has baked variants and fallbacks for; and its `bake.toml`; and (since 1.2.0) what its code reaches for outside the game (8.10), each kind with up to three classes and three of the things named, the notable kinds marked `!`, or `Reaches for: nothing outside the game`. A jar without a readable `miracle.mod.toml` exits 1; a missing jar is a heresy.

#### `scriptorium [--idea] [--vscode] [--eclipse]` (alias `ide`)

Inside a project, writes IDE project files; without options, all of them:

- IntelliJ IDEA: `.idea/misc.xml` (JDK 25, language level 25), `.idea/modules.xml`, a module `.idea/<id>.iml` (source folder `src/`, resource folder `resources/`, `build/`, `run/` and `.idea/` excluded) and one module `.idea/fallback-<version>.iml` per `fallback/<version>/` (source folder `src/`, depending on the main module, with that version's readable API (6.4) instead of the primary Minecraft jar); run configurations `Bake`, `Pray (client)` and `Pray (server)` running `miracle.jar` with the project JDK.
- VS Code and Eclipse (`--vscode` and `--eclipse` are the same): `.project`, `.classpath` (`src/`, `resources/`, JavaSE-25, output `build/eclipse`), `.settings/org.eclipse.jdt.core.prefs` (compliance 25), `.vscode/extensions.json` (recommending the Extension Pack for Java) and `.vscode/tasks.json` (`miracle: bake`, the default build task, `miracle: pray client`, `miracle: pray server`). Fallbacks aren't part of this project.

The class path is the one `bake` compiles against (the loader, the library if the mod depends on it, the primary Minecraft version and its libraries), downloading what's missing; `<name>-sources.jar` next to a jar is attached as its sources (the build and the release zip have them for the loader and the library). Paths are absolute, so the files are for one machine: the IDE entries are added to the project's `.gitignore` if missing (`genesis` writes them there from the start). Existing IDE files are overwritten. Exits 0.

#### `consecrate <instance>` (alias `install`)

Installs MiracleLoader into a Prism Launcher instance. Prism's data folder is `--prism <folder>`, else `PRISM_DATA`, else the first of these that has an `instances` folder: on Linux `~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher` (Flatpak), then `$XDG_DATA_HOME/PrismLauncher` (default `~/.local/share/PrismLauncher`); on macOS `~/Library/Application Support/PrismLauncher`; on Windows `%APPDATA%\PrismLauncher`, then `~\scoop\persist\prismlauncher`. None: a heresy listing where it looked.

- The instance is named by its folder, or by the name Prism shows (`name=` in `instance.cfg`), ignoring case; the rest of the arguments, joined by spaces, are the name. None given, or no match: the instances are listed and it's a heresy.
- It MUST refuse while a Prism Launcher process is running (Prism rewrites `mmc-pack.json` on exit), and on an instance with Fabric, Quilt, NeoForge, Forge or LiteLoader.
- Install: `libraries/miracle-loader-<version>.jar` (older `miracle-loader-*.jar` removed), `patches/io.github.hronosin.miracle.json` (a component: `mainClass` `io.github.hronosin.miracle.Resurrection`, the jar as a local library, requiring `net.minecraft`, order 10), the component added to `mmc-pack.json` (backed up to `mmc-pack.json.bak` first; other components kept as they were), and `miracle-toolchain.jar` into the game folder's `mods/` (`.minecraft` or `minecraft`), if the library is next to the toolchain. `--examples` also copies the example mods found next to it (a checkout's `build/`). An obfuscated version gets a note: mods need a variant baked for it.
- `--uninstall` removes the component, the patch and the loader jar, and leaves `mods/` alone. `--list` lists the instances, marking those with MiracleLoader `[consecrated]`.

`prism-install.sh` and `prism-install.cmd` in a checkout build first, then run `consecrate --examples`.

#### `exorcise [--yes]` (alias `clean`)

Inside a project, lists `build/`, and `logs/`, `crash-reports/` and `debug/` in every `run/` folder, with sizes. With `--yes`, deletes them. It MUST NOT delete worlds, bonfires, configs, sources or anything else. Exits 0.

#### `scribe item|block|entity <name>` (alias `assets`)

```
miracle scribe item <name> [--title "Holy Water"] [--force]
miracle scribe block <name> [--title "Altar of Miracles"] [--force]
miracle scribe entity <name> [--title "Heretic"] [--egg zombie | --no-egg] [--model] [--force]
```

Writes, under the project's `resources/`, what a new item, block or entity needs besides code (9.11). The namespace is the project's id with `-` as `_`:

| kind | files |
|---|---|
| item | `assets/<ns>/items/<name>.json` (model definition), `assets/<ns>/models/item/<name>.json` (`item/generated`), `assets/<ns>/textures/item/<name>.png` |
| block | `assets/<ns>/blockstates/<name>.json`, `assets/<ns>/models/block/<name>.json` (`block/cube_all`), `assets/<ns>/items/<name>.json` (the block's item uses the block model), `assets/<ns>/textures/block/<name>.png`, `data/<ns>/loot_table/blocks/<name>.json` (drops itself unless blown up) |
| entity | `data/<ns>/loot_table/entities/<name>.json` (drops nothing, edit to taste) and a spawn egg `<name>_spawn_egg`: `assets/<ns>/items/<name>_spawn_egg.json` pointing at vanilla's `minecraft:item/<egg>_spawn_egg` with `--egg <egg>`, or at its own `item/generated` model and placeholder texture without; `--no-egg` writes neither the egg nor the loot table (a projectile, say); `--model` also writes `assets/<ns>/geo/<name>.geo.json` (a biped in Bedrock's geometry format: `body`, `head`, `rightArm`, `leftArm`, `rightLeg`, `leftLeg`, laid out like a player skin) and `assets/<ns>/textures/entity/<name>.png` (a 64×64 placeholder in that layout, with a face), for `Being.sculpted()` (9.11) |

**Sounds** (since 1.1.0):

```
miracle scribe sound <name> <file> [--music] [--title "Bell tolls"] [--force]
```

Brings a sound file into the mod as `assets/<ns>/sounds/<name>.ogg` and an event `<name>` (with `/` as `.`) in `assets/<ns>/sounds.json`. The game plays Ogg Vorbis only, and plays a sound from a place in the world only if it is mono. So an Ogg Vorbis file that is mono (or any Vorbis for `--music`) MUST be copied unchanged; anything else is converted by `ffmpeg` (`libvorbis`, then its own `vorbis` encoder) or, for wav, flac and aiff, `oggenc`: to mono, or with `--music` to stereo. Without either, it is a heresy that says how to install ffmpeg on each system; nothing is left behind. The kind of an Ogg file is read from its first packet (Vorbis, Opus, FLAC, Speex). `--music` variants are `"stream": true` and get no subtitle unless `--title`; others get `"subtitle": "subtitles.<ns>.<event>"` and that key in `en_us.json` (`--title`, or the name's last part capitalized). The same name again (without `--force`) adds the file as `<name>_2.ogg`, `_3`... and another variant of the event, which the game picks from at random; `--force` replaces the event and its file. Other entries of `sounds.json` are kept.

All kinds add `item.<ns>.<name>`, `block.<ns>.<name>` or `entity.<ns>.<name>` to `assets/<ns>/lang/en_us.json` (entities with an egg also `item.<ns>.<name>_spawn_egg`, "<Name> Spawn Egg"), keeping its other entries; the name is `--title`, or the id's words capitalized. Textures are 16×16 placeholders coloured from a hash of the id (a gem for items and eggs, a framed tile for blocks). Existing files and entries MUST be kept unless `--force`; each file is reported as `wrote` or `kept`. Another kind, or `--egg` on anything but an entity, is a heresy.

#### `dictionary <versions>` (alias `mappings`)

```
miracle dictionary 1.21.11 "26.*" ">=26.2" latest
miracle dictionary --list
```

Fetches the dictionaries for the given versions and patterns (4.1): the client jar (hard-linked to the cached client where the file system allows, copied otherwise) and, for an obfuscated version, Mojang's mappings. Already-fetched files are verified by hash and not downloaded again. `bake` does this by itself for a project's targets; this command is for scripts (the build uses it) and for looking. Without arguments, or with `--list`, it lists the cached dictionaries.

### 5.3 Plumbing

| command | does |
|---|---|
| `classpath <v>` | prints the client jar and the libraries version `<v>` compiles against, separated by the platform's path separator (`:`, or `;` on Windows), on the **last** line of output (download progress may come before it), downloading what's missing. Used by the build. |
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

RGCT targets are references too: on every version, the target class and method MUST exist. References into the JDK, the loader and third-party libraries (Brigadier, DataFixerUpper...) are not checked.

**Library mods.** Classes of a library mod given with `--lib` (the toolchain passes the library for projects that use it) are looked *through*: a mod class extending a library class that extends a game class (a `Reliquary` subclass, say) inherits game members through it, and its overrides of game methods are game overrides. Without `--lib`, such references end at the library class and are counted as `into libraries unchecked`, and on obfuscated versions the overrides would keep their readable names and never be called.

### 6.3 Variants

For each target where every reference resolves, `miracle-bake` writes a **variant**, a full copy of the mod's classes, to `META-INF/miracle/baked/<v>/`, when the target is obfuscated, or when it is unobfuscated but has fallbacks (6.4) that change code. An unobfuscated target without fallbacks needs no variant: the main classes run as they are.

In an obfuscated variant, the following are translated through the mappings:

- class names, member names and descriptors, in code, invokedynamic (lambda) sites and method handles;
- method names that override game methods, directly or through a `--lib` library class, and every call to such a method, whoever's class it's called on;
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
java -jar miracle-bake.jar [--strict] [--lib LIB.jar]... (--native V=CLIENT_JAR | --obf V=CLIENT_JAR,MAPPINGS_TXT)... MOD.jar...
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

- exit code 0, no crash report written during the run and no fatal cause seen (below): `Amen.`, exit 0;
- otherwise: `YOU DIED`, the game's exit code, and the newest crash report written during the run (from `crash-reports/`, by modification time). `pray` exits with the game's code, or 1 if the game exited 0 after crashing (a dedicated server does, and so does a client that found no graphics).

The game's output passes through byte for byte, stdin included (a server's console works), while `pray` watches it for a few known causes of death and, if one shows up, explains it after `YOU DIED` as `What killed it: ...`:

| cause | seen as | fatal with exit 0 |
|---|---|---|
| no graphics | `No context is current`, `No supported graphics backend was found`, WGL and GL context failures | yes |
| no display | `Unable to initialize SDL: No available video device`, `No X11 DISPLAY variable was set`, `The DISPLAY environment variable is missing` (GLFW, before 26.3) | yes |
| port taken | `FAILED TO BIND TO PORT`, `Address already in use` | no |
| out of memory | `java.lang.OutOfMemoryError` | no |

**Yukari.** After a heresy, a file system error or a death, Yukari Yakumo remarks on it from a gap, on a line of its own starting with `  Yukari, from a gap:`. The remark comes after the plain explanation, never instead of it, and depends only on the error (the same error, the same remark). `MIRACLE_YUKARI=0` or `-Dmiracle.yukari=false` silences her.

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
4. `entangles` (since 1.1.0): an entry naming a mod that isn't there is ignored; one whose version is too old is a warning, and no entanglement. Otherwise the other mod loads first, unless it already needs this one (through `depends` or earlier entanglements, directly or through others): then that entanglement is dropped with a warning, because someone has to go first. Entanglements are added mod by mod, in id order, each only if it closes no circle. A mod entangling itself, or `miracle`, stops the game. The mod list in the log says `entangled with ...`.

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
| `graphicsBackend()` | since 1.3.0: the graphics backend the game is set to use, known before it starts: `"opengl"`, `"vulkan"`, `"default"` (the game picks when it starts) or `"none"` (a server) (8.10) |
| `entangled(id)` | since 1.1.0: the mods `id` is entangled with (8.2), in its `entangles` order; empty for a mod with none, or one that isn't there |

### 8.6 Patching on behalf of dependents

`rgct.onBehalfOf(id)` returns an `Rgct` view whose patches are registered in the name of mod `id`, so the report, the conflict checks and crash blame name that mod. It MUST only be allowed when mod `id` lists the caller's mod in its `depends`, and only before the freeze.

### 8.7 `miracle.lock`

After the freeze the loader writes down what every mod patches, one line per patch, and keeps it in `miracle.lock` next to the mods folder (`-Dmiracle.lockFile` elsewhere):

```
game 26.3 server
mod hallelujah 1.0.0
  net.minecraft.world.entity.LivingEntity#getJumpPower()F intercept@RETURN [modifies return]
  net.minecraft.server.MinecraftServer#tickServer(Ljava/util/function/BooleanSupplier;)V @RETURN [observes]
mod miracle-toolchain 1.3.0
  ...
```

- A line is `<class>#<method><descriptor> <where> [<effects>, priority n]`, effects as in the report (`observes`, `reads only`, `modifies return`, `sets args`, `cancels`, `cancels with a value`...); `<class> raw [whole class]` and `<class> rawBytes [whole class]` for raw patches; the same hook twice reads `x2`. On an obfuscated game, names come from the baked variants' `rgct-names.txt` where available. Lines are sorted, mods by id: the file diffs well in version control.
- Patches made through `onBehalfOf` (8.6) count for the dependent mod: a library's patches are listed under the mod that asked for them.
- Each start compares. No lock yet: pin it. A lock pinned on another game (version or side): pin afresh, saying so. Same patches: say so; if only versions changed, rewrite quietly. Different patches: print, per mod, `<id> <old> -> <new>` (or `(new)`, `(gone)`, `(same version, different patches: a setting?)`) and the `+`/`-` lines.
- `-Dmiracle.lock`: `update` (default) prints the difference and pins the new state; `strict` prints it and stops the game without touching the file (delete it, or start once with `update`, to accept); `off` does nothing. Another value stops the game.
- An unreadable lock is reported and replaced.

### 8.8 Resurrection

MiracleLoader needs Java 25 (the ClassFile API); its bytecode can't be downgraded. A launcher starts each game version with the Java that version asks for (21 for 1.21.11), so the loader jar has a second main class, `io.github.hronosin.miracle.Resurrection`, compiled for Java 8. Launchers SHOULD use it (`consecrate` does); `MiracleMain` stays for launchers that already run Java 25.

- On Java 25 or newer it calls `MiracleMain.main` in the same process.
- On an older Java it prints which Java it was started with (and, from the class path, which version the game asks for), looks for Java installations, and picks the newest that is 25 or newer. Where it looks, in order: `-Dmiracle.java` or `MIRACLE_JAVA`; `JAVA_HOME`; then, unless `-Dmiracle.javaSearch=explicit`, every `java` on the `PATH` and the usual places (system JVM folders, SDKMAN, `~/.jdks`, Prism Launcher's and the official launcher's runtimes, Adoptium, Zulu and Microsoft on Windows, macOS's `JavaVirtualMachines`). A Java's version comes from its `release` file, or from asking it.
- It then starts `<java> -XX:+IgnoreUnrecognizedVMOptions <its own JVM options> -Dmiracle.resurrected=<old version> -cp <its class path> io.github.hronosin.miracle.MiracleMain <arguments>`, with the same standard streams, and exits with the game's exit code. Stopping it stops the game.
- With no Java 25 or newer, it prints `No Java 25 or newer was found, so there is no miracle today.`, what it found, and how to fix it, and exits with 1.

### 8.9 The Java agent

`miracle-loader.jar`'s manifest names `Premain-Class: io.github.hronosin.miracle.Agent` (compiled for Java 8), so the loader also runs as `-javaagent:miracle-loader.jar[=<mods folder>]` with the game's own main class:

- On a Java older than 25 it prints which Java it got and where to change it, and exits with 1.
- Otherwise, before the game's `main`: mods are found (in the folder after `=`, else `miracle.modsDir`, else `mods`), resolved, and appended to the system class path; a mod's baked variant (8.3) is copied into a temporary jar appended before the mod's own, whose classes still count as the mod's (`Mods.owner`). Then the same steps as 8.4: `transform()`, freeze, report, lint, `miracle.lock`, the too-early check, and `onLaunch()`. A class-file transformer applies RGCT to every class loaded afterwards outside the JDK; if patching fails, the game stops with the crash banner instead of loading the class unpatched.
- An exception escaping the game's main thread gets the same crash banner and blame as under `MiracleMain`, and exit code 1.
- `miracle.target` and `miracle.gameClasspath` don't apply; everything else in 4.2 does.
- Both at once (since 1.4.1): if the agent has run, `MiracleMain` (and so `Resurrection`) only says so and calls the game's main (`miracle.target`) from the class path, which the agent already patches. The mods are loaded once.

### 8.10 Reach (since 1.2.0)

Right after the mod list, the loader reads every mod jar's classes (and fallbacks) without loading them, and notes what their constant pools name outside the game:

| kind | what counts | notable |
|---|---|---|
| starts processes | `ProcessBuilder`, `Runtime.exec` | yes |
| loads native code | `System`/`Runtime` `load`/`loadLibrary`, `java.lang.foreign.Linker`/`SymbolLookup` | yes |
| uses the network | `Socket`, `ServerSocket`, `DatagramSocket`, `URLConnection`, `HttpURLConnection`, `java.net.http.*`, socket and datagram channels, `URL.openConnection`/`openStream` | yes |
| makes classes from bytes | `ClassLoader.defineClass`, `Lookup.defineClass`/`defineHiddenClass`, `URLClassLoader` | yes |
| uses Unsafe | `sun.misc.Unsafe`, `jdk.internal.misc.Unsafe` | yes |
| calls OpenGL or Vulkan directly | anything in `org.lwjgl.opengl`, `org.lwjgl.opengles`, `org.lwjgl.vulkan`, named from outside `org.lwjgl` | yes |
| can end the game itself | `System.exit`, `Runtime.exit`/`halt` | yes |
| writes, moves or deletes files | `Files` writing, moving, copying, deleting and creating methods, `FileOutputStream`, `FileWriter`, `RandomAccessFile`, `File.delete`/`renameTo`/`mkdirs`... | no |
| opens private members | `setAccessible`, `trySetAccessible`, `MethodHandles.privateLookupIn` | no |

A mod that names notable kinds gets one line: `Reach: <id> starts processes, uses the network. (miracle zandatsu shows where.)`. This is a label, not a verdict and not a sandbox (Java 25 has none): it sees what the code names, not what it does; reflection can hide more, and naming isn't misusing.

**The backend** (since 1.3.0). On clients, before the mods' code runs, the loader works out which graphics backend the game is set to use and logs it (`Graphics: Vulkan (options.txt)`): `-Dmiracle.backend=opengl|vulkan` if the player says so; otherwise (`auto`, the default) `preferredGraphicsBackend` in `options.txt` in the game folder (`--gameDir`, else the working folder): `"opengl"`, `"vulkan"` or `"default"`, which is also what a missing file or setting means. Versions before 26.2 have OpenGL only, whatever is set. Servers: `none`. Another `-Dmiracle.backend` value stops the game. `Mods.graphicsBackend()` returns it; what the game ends up running is Event Horizon's `Lensing.backend()` (9.15).

**Raw graphics.** A mod that calls OpenGL or Vulkan past the game's own API (blaze3d, renderpearl) breaks other mods' rendering, and doesn't work on the other backend. `-Dmiracle.rawGraphics`: `warn` (default) logs, per mod, the first such call and where, and what that means for the backend the game is set to (can work here; won't work here; won't if the game picks the other); `refuse` stops the game listing every such mod; `allow` says nothing (the kind still shows in the `Reach` line). Another value stops the game. Servers draw nothing, so the rule doesn't apply there. `bake` warns the author about the same, after baking.

## 9. The library

### 9.1 What it is

`miracle-toolchain.jar` is a mod (id `miracle-toolchain`, `depends = ["miracle>=1.0.0"]`) with an entrypoint. Its package is `io.github.hronosin.miracle.toolchain`. It has no privileges a mod couldn't have: everything below is built on RGCT and the API in section 8.

| part | alias | covers |
|---|---|---|
| `Omens` | `Events` | things that happen (9.4) |
| `Blessings`, `Blessing` | `Tweaks` | well-known game values with merge rules (9.5) |
| `Sermons` | `ChatCommands` | commands (9.6) |
| `Commandments` | `Config` | settings files (9.7) |
| `Scripture` | `Resources` | the mod jar's `data/` and `assets/` (9.8) |
| `Proclamations` | `Notices` | telling players things (9.9) |
| `Creation`, `Relic`, `Being` | `Content` | new items, blocks and entities (9.11) |
| `Shrine`, `Sanctuary`, `Vigil`, `Hallowed`, `Reliquary`, `Altarpiece`, `Vision` | | block entities, their blocks, ticking, inventories, their renderers, menus and screens (9.11) |
| `Telepathy`, `Scroll` | `Networking` | messages between client and server (9.12) |
| `Gestures` | `Keybinds` | keys (9.13) |
| `Communion` | `Handshake` | comparing mods when a player joins (9.14) |

An alias is a subclass that adds nothing; `Events.playerJoined(...)` *is* `Omens.playerJoined(...)`.

### 9.2 When to call it

Handlers and changes MUST be added in `onLaunch()` or later, never in `transform()`.

The reason is class loading. A handler whose parameter is, say, a `ServerPlayer` loads `ServerPlayer` (and `Player`, `LivingEntity`, `Entity`) the moment the handler is created. During `transform()` that would put those classes beyond anyone's reach to patch (8.4). So the library does its patching up front (9.3), and mods only hand over their handlers later, when using game classes is safe.

Subscribing before launch (any `Omens` method, a change on a `Blessing` (`multiply`, `add`, `clamp`, `set`), `Sermons.preach`, `Scripture.reveal`, `Creation.item`/`block`/`entity`/`shrine`/`reliquary`/`vision`, a channel's `onServer`/`onClient`, `Gestures.key`) MUST fail with an error that says to use `onLaunch()`. Merely building a `Blessing` (`Blessings.jumpPower()`, `forPlayers()`, `when(...)`) doesn't. `Commandments`, `Proclamations`, `Telepathy.channel(...)`, `Communion.bothSides()`/`eitherSide()` and `Scroll`s involve no hooks and MAY be used any time (`Proclamations` and sending need a live game, of course).

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
| `Creation.item`/`block`/`entity`/`shrine`/`reliquary`/`vision` (or `Content.`) | the registry and creative-tab hooks (library's name), and the pack hook, already revealed (the mod's things need their assets); with `entity`, also the attribute, data-fixer and (clients) renderer hooks; with `vision`, also (clients) the screen and caption hooks |
| `Shrine.enshrines`/`renderedBy` | (clients) the block entity renderer hook |
| `Being.spawns` | the natural spawning hooks |
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
  - Looks, by name so a dedicated server never loads a renderer: `looksLike("zombie")` borrows a vanilla entity's renderer (the entity SHOULD extend that entity's class, whose fields the renderer reads); `looksLikeItem()` draws the carried item (`ThrownItemRenderer`, for `ItemSupplier` entities such as `ThrowableItemProjectile`s); `renderedBy("com.example.MyRenderer")` makes the mod's own `EntityRenderer`, which needs a public constructor taking `EntityRendererProvider.Context`; `sculpted()` draws a model of the mod's own (below). With none, or a vanilla name the game can't draw, the entity is invisible and the log says so.
  - `spawns(weight, min, max, biomes...)`: natural spawning (below).

**Models.** `sculpted()` draws a mob from `assets/<ns>/geo/<name>.geo.json`, a geometry in Bedrock's format (what Blockbench saves for "Bedrock Entity" and "Generic Model" projects), painted with `assets/<ns>/textures/entity/<name>.png`; `sculpted(geometry, texture)` names other files (`"ns:name"` is `assets/ns/geo/name.geo.json`; the texture is a full id). The first geometry in the file is used:

- bones become model parts, under their `parent` (a missing parent is noted, and the bone hangs from the root); a bone's `pivot` and `rotation` become the part's pose;
- cubes become boxes: `origin`, `size`, box `uv`, `inflate`, `mirror` (a cube's own, or its bone's). Bedrock's origin is at the feet with Y up and every coordinate absolute; Java's is 24 pixels up with Y down, relative to the part's pivot. A part is at `(px, 24 − py, pz)`, or `(px − ppx, −(py − ppy), pz − ppz)` under a parent pivoted at `pp`; a box at `(ox − px, py − oy − h, oz − pz)`; rotations carry over as they are, in degrees (Blockbench exports them in Java's sense already);
- a cube with a `rotation` becomes a part of its own, `<bone>_r<n>`, turned about the cube's `pivot` (Java boxes can't rotate);
- per-face UV (which Java models can't do) is read as box UV from the north face, with a warning; `description.texture_width`/`height` size the texture (64 by default).

Parts move by name: the part named `head` (any case) follows the mob's gaze; parts with `leg` in their name swing as it walks (1.4 × the walk speed), parts with `arm` against them (1.0 ×); `right` in the name goes first, `left` half a step later, and unnamed pairs alternate. Everything else keeps the pose the file gives it. The model is read again on every resource reload (F3+T). A file that is missing or can't be read is logged with the reason, and the mob is drawn as a 16-pixel block. Sculpted beings MUST be `Mob`s; they are drawn by a `MobRenderer` with a shadow of half their width.

**Animations.** If `assets/<ns>/animations/<name>.animation.json` exists (the geometry's name; Bedrock's format, what Blockbench exports), its animations play by the last part of their names (`animation.heretic.walk` is `walk`):

- `idle` loops while the mob stands; `walk`, `walking`, `move` or `run` loops at the mob's life time, weighted by how fast it walks (`min(1, 1.5 × speed)`), and idle fades out as it does; `attack` or `swing` plays through a melee swing (its progress × the animation's length); `death` or `die` plays while it dies (`deathTime / 20` seconds). Other names are logged and not played.
- Each frame starts from the file's pose. Without a walking animation, parts still move by name as above; with one, they don't. The `head` still looks where the mob looks, on top of any animation.
- Channels: `rotation` (degrees, added), `position` (pixels, added, Y up as in Bedrock), `scale` (multiplied). A channel is a constant or keyframes by time; a keyframe is a value or `{pre, post, lerp_mode}`, `linear` or `catmullrom`. `loop`: `true` wraps, `hold_on_last_frame` and `false` hold the end; `animation_length` defaults to the last keyframe.
- A value is a number or Molang: numbers, `+ − * /`, comparisons, `&&`, `||`, `!`, `?:`, parentheses; `math.` `sin`, `cos` (degrees), `abs`, `sqrt`, `exp`, `ln`, `floor`, `ceil`, `round`, `trunc`, `min`, `max`, `pow`, `mod`, `clamp`, `lerp`, `random`, `pi`; `query.`/`q.` `anim_time`, `life_time`, `ground_speed`, `modified_move_speed`, `modified_distance_moved`, `head_x_rotation`, `head_y_rotation`. `variable.` and `temp.` read as 0; statements (`;`, assignments) and unknown queries are logged and read as 0.
- The file is read again on every resource reload; one that can't be read is logged, and the model moves by part names instead.

**Natural spawning.** `spawns(weight, min, max, biomes...)` lets a being turn up by itself in groups of `min` to `max`, in the given biomes (ids, or `#tags`), with `weight` against the other mobs of its category there. It MAY be called more than once. Everything else follows the entity type's `MobCategory`: which cap it counts against; where it spawns (water categories in water, the rest on the ground, by the `MOTION_BLOCKING_NO_LEAVES` heightmap); and its rules (`MONSTER`: `Monster.checkMonsterSpawnRules`, in the dark and not in peaceful; `CREATURE`: `Animal.checkAnimalSpawnRules`, on grass in the light; others: `Mob.checkMobSpawnRules`). A `MISC` being never spawns naturally, and the log says so. It spawns during play, not when chunks are generated.
- Names: `entity.<ns>.<name>` in the lang file. Drops: `data/<ns>/loot_table/entities/<name>.json`, none without one.

Mechanics:

| step | hook |
|---|---|
| blocks, in call order, then entity types, then items (spawn eggs need their type), registered after vanilla's and before the registries freeze; each new block state gets the next network id, in the same order on every side | `BuiltInRegistries#freeze()V` (head) |
| items added to their tabs whenever the game fills a tab | `CreativeModeTab#buildContents(ItemDisplayParameters)V` (return) |
| our types' attributes answer before vanilla's map | `DefaultAttributes#getSupplier(EntityType)` and `#hasSupplier(EntityType)` (head) |
| our types need no save-data fixer, and the builder doesn't log an error about it | `Util#fetchChoiceType(TypeReference, String)` (head, for our ids) |
| renderers join the game's providers before anyone reads them (clients) | `EntityRenderers#createEntityRenderers(Context)` and `#validateRegistrations()` (head) |
| our beings join the mobs that may spawn at a spot, where their biomes match (the method's parameters changed in 26.3, so it is hooked by name and copes with both) | `NaturalSpawner#mobsAt` (return) |
| where and how they spawn | `SpawnPlacements#getPlacementType(EntityType)`, `#getHeightmapType(EntityType)` and `#checkSpawnRules(EntityType, ServerLevelAccessor, EntitySpawnReason, BlockPos, RandomSource)` (head, for our types) |


**Block entities.**

```java
Relic<Block> altar = Creation.block("altar", p -> new Sanctuary(p.strength(2f)));
Shrine<AltarEntity> altarEntity = Creation.shrine("altar", AltarEntity::new, altar);
Shrine<Reliquary> stash = Creation.reliquary("stash", 3, Creation.block("stash", p -> new Sanctuary(p)));
```

- `shrine(name, factory, blocks...)` makes a block entity type held by `blocks` (at least one; each MUST be a `Relic` from `Creation.block`, and holds one shrine at most). `factory` makes a block entity for a position and state, usually a constructor. Names and ids as for items; the same shrine id twice MUST fail. `Shrine` is to block entity types what `Relic` is to items: `get()`, `exists()`, `id()`, and `create(pos, state)`.
- Holding blocks MUST be `EntityBlock`s; one that isn't is logged. `Sanctuary` is a `Block` and `EntityBlock` that finds its shrine by itself (the one naming it) and:
  - makes the block entity through the shrine;
  - ticks it if it implements `Vigil` (`serverTick()` on the server, `clientTick()` on clients, both empty by default): the first block entity made tells, and a shrine whose entities don't keep vigil gets no ticker at all;
  - opens its menu on a right click with nothing to use (`useWithoutItem`) when the block entity is a `MenuProvider`, and reports it as the block's menu provider.
  It MAY be extended for shapes, facing and the rest.
- `Hallowed` is a `BlockEntity` whose saved data (`saveAdditional`) is sent to nearby clients when their chunk loads and whenever `sync()` is called (which also marks it to be saved).
- `enshrines(slot)` shows the item in the block entity's container slot `slot` above the block, turning slowly and bobbing: the same on every version. A `Reliquary` whose shrine enshrines a slot sends every change of its items to nearby clients by itself; other block entities MUST sync their items.
- `renderedBy("com.example.AltarRenderer")` draws it with a renderer of the mod's own: a class with a public constructor taking `BlockEntityRendererProvider.Context` that either extends `Altarpiece<T>`, whose `render(blockEntity, pose, collector, light, partialTick)` is called every frame on every supported version (the library implements the game's interface around it; `viewDistance()` defaults to 64), or implements the game's `BlockEntityRenderer` itself (full control; its `submit` takes a `CameraRenderState`, which moved between 1.21.11 and 26.1). A class that can't be made is logged, and the shrine falls back to enshrining slot 0.
- `Reliquary(type, pos, state, rows)` is a container block entity with `rows` × 9 slots (1 to 6; other counts MUST fail): saved with the world (`ContainerHelper`), dropped when the block goes, reachable by hoppers, named after its block (or an anvil's name), opened in vanilla's chest menu of that many rows. `createMenu(id, inventory)` MAY be overridden for a menu of one's own; `sync()` sends the items to nearby clients. `reliquary(name, rows, blocks...)` is `shrine` with a plain Reliquary.

**Menus.**

```java
Vision<AltarMenu> altarMenu = Creation.vision("altar", AltarMenu::new)
        .caption(menu -> Component.literal(menu.progress() + "%"));
```

- `vision(name, factory)` makes a menu type; `factory` makes the client's half from a container id and the player's inventory (the server fills it in as the screen opens). `Vision`: `get()` (the `MenuType`), `exists()`, `id()`. The server's half is made by whoever opens it: a `Reliquary`'s `createMenu`, or `open(player, title, factory)`, which opens it from the server (a no-op on clients).
- Screens, by name so a dedicated server never loads one: a menu extending `ChestMenu` gets vanilla's chest screen; otherwise `screen("com.example.AltarScreen")` names an `AbstractContainerScreen` with a public constructor taking (the menu or a supertype, `Inventory`, `Component`). With neither, or a class that can't be made, the screen doesn't open and the log says why.
- `caption(function)` is a line of text drawn at the right end of the screen's title row, asked for every frame from the client's menu; null or empty draws nothing. It works on any screen of the vision, and is the portable way to show a number: screen code itself differs between versions (26.x draws through `GuiGraphicsExtractor`, 1.21.11 through `GuiGraphics`), so a screen class of one's own may need a fallback per version (6.4).
- `screen` and the other setters MUST be called before the registries are built; `caption` MAY be changed any time.

Mechanics (in addition to the table above):

| step | hook |
|---|---|
| block entity types right after blocks, menu types after items, made through their private constructors (found by shape, not name) | `BuiltInRegistries#freeze()V` (head) |
| our menus' screens, made instead of vanilla's lookup (clients) | `MenuScreens#create(MenuType, Minecraft, int, Component)V` (head, for our types) |
| captions (clients) | `AbstractContainerScreen#extractLabels(GuiGraphicsExtractor, II)V` (return); on 1.21.11 `#renderLabels(GuiGraphics, II)V`, through the library's fallback |
| block entity renderers join the game's providers before anyone reads them (clients) | `BlockEntityRenderers#createEntityRenderers(Context)` (head) |

Blocks, items, entity types, block entity types, menu types and block states travel as numbers, so client and server MUST have the same mods creating the same things in the same order; Communion (9.14) checks it at the door.

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

**The manifest** (payload `miracle:communion`, both directions): `Scroll` values `int 0x4D434D31`, `string` library version, `int n`, then per mod `string id`, `string version`, `boolean bound`, then `long` creation fingerprint (FNV-1a, 64 bits, over `block|entity|item|block_entity|menu <id>` lines in registration order) and `int` creation count.

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

### 9.15 Event Horizon Extension (experimental)

`event-horizon.jar` is a mod (id `event-horizon`, `depends = ["miracle-toolchain>=…"]`) in package `io.github.hronosin.miracle.horizon`. Everything in it is `@Experimental`: section 12's stability doesn't cover it, and a release MAY change it, saying so in its notes. Mods using it depend on both (`depends = ["miracle-toolchain", "event-horizon"]`); `genesis --horizon` writes that, and `bake`, `pray` and `scriptorium` put its jar on the class path and in `mods/` when a project depends on it.

- `Vec` (immutable, doubles, the game's axes; `look(yaw, pitch)` in the game's degrees), `Quat` (unit quaternions; `yawPitch` rotates south to where an entity with that yaw and pitch looks), `Curve` (`line`, `bezier` of any order, `through`: a centripetal Catmull-Rom spline through every point; `sample`, `evenly` by arc length, `length`, `direction`, `eased`), `Ease` (0 to 0 and 1 to 1, clamped), `Noise` (seeded Perlin, 0 on whole numbers; `fbm`), `Shape` (`sphere`, `box`, `cylinder`, `cone`, `line`, `union`, `minus`; `blocks()`: every block whose center is inside), `Geodesic` (to and from `Vec3` and `BlockPos`; `Frame`: local (right, up, forward), where right of a south-facing look is west).
- `Ballistics.Body(gravity, dragUp, dragSide, gravityFirst)`: each tick, position += velocity, then gravity and drag (gravity first for `LIVING`: 0.08, 0.98, 0.91; after for `ARROW`: 0.05, 0.99, 0.99 and `THROWN`: 0.03, 0.99, 0.99; `VACUUM` has no drag). `inTicks` gives the velocity that lands on a point after exactly n ticks, `after` the point after n ticks, `aim` the first (or with `high` the last) flight time up to 400 ticks whose velocity is at most a speed. No rounding: the game's steps are summed exactly.
- `Singularity`: `look`, `ray` (blocks by collision shape, fluids ignored; entities that are pickable and not spectators for `look`), `clear`, `entities` (by the middle of the body, nearest the shape's center first), `blocks`.
- `Tidal`: `push`, `set`, `pull`, `away`, `attract`, `launch`, `throwAt`, `stop`, `body`. Changes to motion are sent to clients (players' own included) the way the game marks a hit.
- `Accretion`: `boost(entity, attribute, id)` with `add`, `multiplyBase`, `multiplyTotal`, `value` (a formula, every tick; 0 removes the modifier), `when` (checked every tick), `forTicks`; transient modifiers, replaced by the same id, gone when the entity unloads. `stat(id, initial)`: objective `<namespace>.<path>`, created on first write, whole numbers clamped by `range`, `get`/`set`/`add`/`spend`/`reset`, `onChange` for changes made through it; server side; a mob's stats are reset when it dies.
- `Ergosphere`: `around(entity, radius)` or `at(level, center, radius)`, `affecting(type, filter)`, `does`, `every` (20 by default), `shape`, `forTicks`; ends with its entity.
- `Redshift`: `server()` (default) and `client()` clocks; `in`/`after`, `repeat`/`every` (`times`), `during`/`over` (progress 0 to exactly 1 over n calls); `bound(entity)`; a failing task is cancelled and logged. `cooldown(ticks)` per entity. Everything scheduled, every boost and aura, and every cooldown ends when the server stops.
- `Hawking`: `points`, `line`, `circle`, `sphere` (Fibonacci), `helix`, `curve`, `outline` (server side), and the same shapes as lists of points.
- `Spaghettification`: `stretch` (at once or eased over ticks), `restore`, `factor`: a `SCALE` modifier with id `event-horizon:spaghettification`.
- `QuantumFoam` (0.2): streams are `Foam`s (a `java.util.random.RandomGenerator`; xoroshiro128++): `random()` (per thread, securely seeded), `seeded(seed)`, `keyed(seed, key...)` and `of(level, key...)` (the same for the same seed and key, whatever was drawn before; `fork(key...)` keys further from where a stream started), `from(RandomSource)`. Key parts: strings, whole numbers (1 and 1L are the same), other numbers, booleans, characters, UUIDs, enums, `Vec`s, int arrays, entities (by UUID) and block positions; anything else is refused. A world's streams start from the first 8 bytes of SHA-256 of `"event-horizon:quantum-foam:" + seed`, never from the seed itself. On `Foam` and, for `random()`, statically: `chance`, `maybe`, `between` (whole numbers: both ends included), `oneOf`, `pick`, `sample` (distinct), `shuffled`; on `Foam`: `gaussian`, `direction` (uniform on the sphere), `inside(shape)` (uniform, empty after 4096 misses), `roll(pool)`. `pool()` / `pool(id)` (named pools are unique and listed by Telescope): `add(item, weight)`, `add(pool, weight)`, `nothing(weight)`; weights from 0 up; `roll` (null for nothing or an empty pool), `roll(n)` (nothings left out), `chanceOf`, `outcomes`. `bag(items)`: every item once per round, never the same item twice in a row across a reshuffle. `pity(p)`: each miss raises the next try's chance by a step chosen so the long-run rate is exactly p, a hit starts over; streaks per owner (entities by UUID), forgotten when the server stops; `longest()` is the most tries it can take. `somewhere(level, center, radius)`, through `Singularity.somewhere`/`standable`: a sturdy top below, no collision and no fluid in the two blocks above, loaded chunks only.
- `Penrose` (0.2): `number(id, base)` (with `range(min, max)`, the owner's bounds), `flag(id, base)`, `choice(id, base)` declare a value, once per id (`namespace:path`); the declaring mod is its owner. `touch(id)` or `touch(id, contextType)` changes a value by id, declared or not yet (layers wait), with `when` (all must hold), `priority` (for `set`), `by(modId)` (default: the mod whose code calls, by its jar), then `add`, `multiply` (numbers or formulas of the context), `clamp`, `set`; each returns a `Layer` (`remove()`). A layer with a context type applies only when the value is read for a context of that type. Numbers: `range(clamp((base + Σ adds) × Π factors))`, base = the winning `set` or the declared base (or, for `apply(base, context)`, the given one); adds and factors are applied in order of mod id, then amount, then the order the layers were made in, so any load order gives the same bits. `set`: the highest priority wins, each mod's last `set` at a priority counts; different values at the same top priority are a conflict and nobody wins (the base stays). Clamps intersect; ranges that don't overlap are a conflict and none of them applies. Choices take only `set` of their type. A layer of the wrong kind or type, a condition or a formula that throws, doesn't count. Conflicts are logged once each (`[Event Horizon] Penrose: ...`) and never thrown; `conflicts(context)` and `explain(context)` show them. Values are read with `get`/`getAsDouble`/`getInt`, with or without a context; without one, numbers with only unconditional constant layers are cached until a layer changes.
- `Telescope` (0.2): `/horizon` for permission level 2: `values`, `why <id>` (for the command's entity: `execute as`), `dice <rolls> <pool>`, and (0.3) `bridges`.
- `Wormhole` (0.3): `to("<id>")` or `to("<id> >= <version>")` makes a bridge from the calling mod; `open(className)` runs it once, if the other mod is there and new enough: the class is loaded by the caller's class loader only then, MUST be a `Runnable` with a public constructor that takes nothing, and is run at once. Returns true if it ran; false if the other mod is missing or too old (`CLOSED`: nothing loaded) or the bridge failed (`FAILED`: not found, not a `Runnable`, a linkage error, or anything it threw; logged with the first lines of the stack, never rethrown). A second `open` on one bridge is an error. Opening a bridge to a mod not in the caller's `entangles` works, with a warning once. `possible()`, `state()`, `why()`, `bridges()`. Services: `offer(id, service)` (`priority(n)`, `withdraw()`), `seek(id, type)` (the offers that are a `type`: the highest priority wins; different offers tied at the top are a conflict, logged once, and nobody wins: empty), `all(id, type)` (highest priority first, then by mod id). `report()` is what `/horizon bridges` shows.
- `Lensing` (0.3): GLSL comes in two dialects: *classic* (1.21.11 to 26.2: `#moj_import`, `in`/`out` without locations) and *separate* (26.3 on: `#include`, `#extension GL_ARB_separate_shader_objects : require` after `#version`, raised to at least 330, and `layout(location = N)` on every top-level `in` and `out` of `.vsh` and `.fsh` files). On clients, every shader file the game loads from a namespace other than `minecraft` (stages and, separately loaded or imported, includes) is translated to the running version's dialect (logged once per file); a file already in it is left as it is, and classic to separate and back gives the same text. Added locations are numbered in declaration order, separately for `in` and `out`, skipping numbers already written; for classic, locations on `in`/`out` are removed. Function parameters, block comments and anything inside braces are left alone; only syntax is translated. `translate(source, dialect, stage)`, `dialectOf`, `dialect()` (of the running game). Post effects: `add`, `remove`, `clear`, `forTicks` on a `ServerPlayer` use the game's own per-player list from 26.3 on (sent to the player, saved with them; `add` is false if it was already there); before 26.3 they return false (said once in the log). `here(id)`, `hereForTicks`, `clearHere`, `showing()` on clients, any version: from 26.3 the effect joins the player's list and stays when the server changes it (a hook on `LocalPlayer#setActivePostEffects`); before, it takes the game renderer's one slot. Hooks: `ShaderManager#loadShader` (and from 26.3 `loadInclude`), clients only.
  (0.4) Uniforms from code: `uniform(effect, name, DoubleSupplier | Supplier<float[]> | float...)`, `clearUniforms(effect)`. A uniform named in a block of a pass of that effect's `post_effect` JSON takes its value from code, worked out before each frame the pass is drawn (render thread); the block's other fields keep the JSON's values. When a block's bytes change, it gets a new buffer (the game's are read-only), laid out by std140 in the JSON's field order, padded to 16 bytes: `float`, `int` (rounded), `vec2`, `vec3`, `vec4`, `ivec3`, `matrix4x4` (column by column); other types are refused. If that machinery fails once, it stops (logged) and the JSON's values stay. `std140(fields, values)` and `Field` are public. `screenEffectScale()`: the player's "Distortion Effects" setting, 0 to 1 (1 outside a client). (0.4.1) `backend()`: what the game actually runs once started, `"opengl"` or `"vulkan"`, from the packages of the GPU device's classes; `"unknown"` before there is one, `"none"` off a client.
  (0.4) A broken effect: when a post effect outside `minecraft` fails to load (26.3: a compilation error), the game's recovery (a resource reload without packs, which ends in a crash for a mod's resources) is skipped: the effect stays off (the game remembers the failure until resources reload), logged once. Before 26.3 the game itself only logs a shader that doesn't compile and draws nothing. Hooks: `ShaderManager#getPostChain`, `#tryTriggerRecovery`, `PostPass#addToFrame`.
- `Chirp` (0.3): sounds by id, nothing registered: `play(level, at, id[, volume, pitch[, source]])` and `play(entity, id[, volume, pitch])` from a place for everyone near; `to(player, id[, volume, pitch])` (master) and `music(player, id)` (music) for one player, at their position; `here(id[, volume, pitch])` on the client, for this player only; `event(id)`.

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
| 26.1, 26.1.1, 26.1.2 | no | checked (every library and example reference present); 26.1.2's client runs, headless, the whole trial included |
| 1.21.11 | yes | baked; client (through Prism, and headless in tests, multiplayer included) and dedicated server run |

Every game method named in section 9 has the same name and descriptor in all of them. A new version is supported once the library bakes (or checks) against it without holes; if a future version renames something, the library gets a fallback for it (6.4), and mods using the library need not change.

"Run" above means started with the loader and mods and exercised. "Headless" means the real client with software OpenGL and no window (26.x: SDL's offscreen driver; 1.21.11: Xvfb), started straight into a world with `--quickPlaySingleplayer` or onto a local server with `--quickPlayMultiplayer`, a test mod pressing keys, throwing things and forging packets from inside: new blocks load in chunks, items and spawn eggs show in their tabs with their names, entities spawn, save, take hits and are drawn by the renderers they asked for, block entities tick, keep their items and progress across a server restart and drop their contents, menus open from a right click and from the server with the screens and captions they asked for (checked in screenshots), block entities are drawn with the items they enshrine and by `Altarpiece`s, sculpted mobs are drawn from their Blockbench models and play their animations (checked in screenshots, idle and frozen mid-swing), new mobs spawn by themselves through the game's own spawner, keys save and fire, messages go both ways, forged batches are caught, and Communion lets matching games in and turns away the rest (a missing mod, an extra one, no library at all, a server without it) with the right lines. What needs eyes (textures, titles, meters) still wants a person.

## 12. Versioning and stability

The loader, the toolchain and the library share one version number, `MAJOR.MINOR.PATCH`. Since 1.0.0 it's semantic versioning, and this is the promise:

- **Within 1.x nothing that's API breaks.** A mod built against 1.m runs on every later 1.x, and one written for 1.m compiles against every later 1.x, unless it used what's excluded below. Removing or changing API waits for 2.0.
- **Deprecation first.** API on its way out is marked `@Deprecated` (with what to use instead) for at least one minor release before 2.0 may remove it.
- **Minor releases** add; **patch releases** fix. A fix MAY change behavior that was plainly a bug (a crash, a wrong value), and its notes say so.

What's API:

- Java: every public type, method and field of `io.github.hronosin.miracle.api` and `io.github.hronosin.miracle.rgct` (MiracleLoader), and of `io.github.hronosin.miracle.toolchain` (the library), including what each is documented to do. Merge rules of layers (RGCT) and of Blessings are API: the same hooks give the same result.
- Files: `miracle.mod.toml` (3.2), `miracle.project.toml` (3.3), `bake.toml` and the mod jar's layout (6.5, 8.1), `miracle.lock` (8.7).
- The command line: command names and aliases, their options, and exit codes (Appendix A); the solemn and boring names (Appendix B).
- The system properties and environment variables of 4.2.
- These output markers, which scripts MAY rely on: `HERESY:`, `Baked:`, `Amen.`, `YOU DIED`, `BONFIRE LIT` (from `bonfire`/`backup`), `NO MIRACLE OCCURRED`, and `[ok]`/`[!!]` in `confess`.

What isn't:

- Anything marked `@io.github.hronosin.miracle.api.Internal` (public only for technical reasons: `HookDispatch`, which patched game classes call; `Shapes`, which mods' redirect lambdas are relinked to; `TransformRegistry`; `Mods.revealed`, `Mods.entangle`, `Mods.launch`, `Mods.standIn`), and anything not public.
- Anything marked `@Experimental`: all of Event Horizon Extension (9.15), which has its own version number, below 1.0 while it settles.
- The bytecode RGCT writes into game classes (and into mod classes that redirect calls), the order of lines in reports, and all other output wording (Yukari included).
- Which game versions are supported (section 11): new ones are added as they come; an old one is dropped only in a minor release that says so.

A mod states what it needs with `depends` (`"miracle>=1.0.0"`, `"miracle-toolchain>=1.0.0"`, `"event-horizon>=0.4.0"`). A mod that uses something added in a minor release (`Mods.entangled`, `entangles`, `against`: 1.1.0) depends on that release: `"miracle>=1.1.0"`.

## 13. Known limits

- Animations play by name (idle, walk, attack, death); there are no animation controllers, no Molang variables, and no way to play a named animation from code yet.
- Natural spawning happens during play; new chunks aren't populated with sculpted beings at generation.
- Screens of one's own are plain game code, and differ between versions; only captions are portable.
- Modrinth doesn't list MiracleLoader as a loader, so `ascend modrinth` can't publish until it does (5.1).
- Lensing translates GLSL syntax, not meaning: includes and uniforms that one version has and another doesn't are the shader's business. Locations it adds follow declaration order, so varyings must be declared in the same order in both stages (or numbered by hand). Server-side post effects need 26.3; before, one effect at a time, from the client. Uniforms from code are for post effects only; mods' own pipelines (shaders for their entities and particles) aren't covered yet.
- Reach (8.10) reads names, not behavior: it's for telling players what a mod reaches for, not for catching a mod that hides it.
- `scribe sound` converts with ffmpeg or oggenc, which the toolchain doesn't bring: only ready Ogg Vorbis files need neither.
- Communion asks bound mods for exactly the same version; there is no way yet to declare a range of compatible versions.
- Mappings are Mojang's only. Yarn and Intermediary ended with 1.21.11, the last obfuscated version, so none are planned.
- `miracle bake` does not fail on holes (6.5); read its report.
- The Prophecy sees only calls written in the dependent mod's own classes (9.3); `Blessing.priority` needs a constant.
- String references to game names outside RGCT targets (reflection) are never translated (6.3).
- Fallbacks compile against the primary version's libraries, not the target's.
- Windows: checked under Wine with stand-in Javas and on one real Windows 11 (in a VM, so without OpenGL: the client stops at its window); `test.sh` is bash (WSL on Windows).
- `bonfire` doesn't check whether the game is running.
- `miracle bake` compiles fallbacks for any `fallback/<v>/` folder, target or not.
- MiracleLoader needs Java 25, so the oldest reachable versions are those that run on it. `Resurrection` (8.8) fixes the launcher's Java, not the game's: the game itself has to work on Java 25.

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
| `consecrate` | `install` | command |
| `scriptorium` | `ide` | command |
| `ascend` | `publish` | command |
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
| `Shrine` | block entity type | library (9.11) |
| `Sanctuary` | block with a block entity | library (9.11) |
| `Vigil` | ticking | library (9.11) |
| `Hallowed` | synced block entity | library (9.11) |
| `Reliquary` | container block entity | library (9.11) |
| `Vision` | menu type and screen | library (9.11) |
| `Altarpiece` | block entity renderer | library (9.11) |
| `sculpted` | custom entity model | library (9.11) |
| `scribe` | `assets` | command |
| the cantor (`scribe sound`) | sound import | command (5.2) |
| `entangles` | soft dependency | `miracle.mod.toml` (3.2, 8.2) |
| `dictionary` | `mappings` | command |
| the Inquisition | packet tripwire | library (9.12) |
| `Verdict.SPARE` / `SMITE` | `ALLOW` / `CANCEL` | library |
| heresy | user error | everywhere |
| the Prophecy | static usage analysis | library (9.3) |
