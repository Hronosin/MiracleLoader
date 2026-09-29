> Forge hammers. Fabric stitches. Miracle just happens.

The first release of a Minecraft mod loader that needs no Gradle, no runtime remapping, and just a little faith.

## What's inside

- **MiracleLoader.** Starts before the game, loads mods from `mods/`, and patches game classes as they load. It has zero dependencies and is built on the JDK's own ClassFile API.
- **RGCT (Runtime Game Class Transformer).** Hooks that observe a method, change its arguments or return value, or cancel it. `rawBytes` is there when you'd rather bring your own ASM.
- **Layers.** When several mods hook the same method, their effects merge by fixed rules instead of overwriting each other: `clamp((base + adds) × factors)`, whatever the load order. When two mods disagree about a value, the game stops with an error naming both of them, and they're warned about it at startup.
- **OSHI (Old School Hook Integration).** Write the mod once against readable names. `miracle bake` checks it against the dictionaries of several Minecraft versions at once and bakes a ready variant for each obfuscated one, so nothing is remapped at runtime. Where the dictionary shows a hole, a one-method *fallback function* fills it, compiled against a readable API jar generated from Mojang's mappings.
- **MiracleToolChain.** `miracle genesis`, `bake`, `pray client|server` and `confess`: create, build and run a mod. It downloads the game, libraries and assets itself and logs in offline, and there's still no Gradle in sight.

## Tested on

| Minecraft | how |
|---|---|
| 26.3 | client (Fedora, Wayland, AMD) and dedicated server, through `miracle pray` |
| 26.2 | client through Prism Launcher, dedicated server |
| 26.1.2 | example mods checked against its dictionary |
| 1.21.11 (obfuscated) | client through Prism and dedicated server, from baked variants |

## Downloads

- **`miracle-toolchain-0.1.0.zip`**: for making mods. Unzip it anywhere, then run `./miracle genesis my-mod`.
- **`miracle-loader-0.1.0.jar`**: just the loader.
- Example mods, each baked for 1.21.11 and checked on 26.1.2, 26.2 and 26.3:
  - `dirt-diamonds`: craft 1 dirt into 1 diamond.
  - `super-jump`: ×1.5 jump power for players.
  - `sprint-jump`: +0.1 jump power while sprinting. Together with super-jump, you can sprint-jump onto a 3-block wall.
  - `jump-counter`: shows your jump count above the hotbar, with a one-method fallback for 1.21.11.

Want the loader in Prism Launcher? Clone the repo and run `./prism-install.sh "Instance Name"` with Prism closed. See the README.

## Requirements

**Java 25.** For 1.21.11 in Prism, set the instance's Java to 25 and skip the compatibility check.

## Known limits

- Java 25 means versions that run on it: 1.20.5+ in principle, 1.21.11 tested.
- Only Mojang's official mappings are supported. Yarn is on the roadmap.
- The library half of MiracleToolChain (events, registries, networking, configs) doesn't exist yet.

## License

MIT. Please also read our [EULA](https://github.com/Hronosin/MiracleLoader/blob/main/EULA.md) very, very carefully.
