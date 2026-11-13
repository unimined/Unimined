# About Unimined

Welcome to Unimined, a unified Minecraft modding environment supporting
modern and legacy Minecraft alike — one Gradle plugin suite covering
everything from alpha-era jar mods to current releases.

## Features

- **Unified environment DSL** — `minecraft { }` binds a source set to a
  version, loaders, and mappings; multiple environments per project or
  across modules.
- **Whole-history loader coverage** — alpha through current releases,
  across the forge, fabric, and bukkit families (see below).
- **Configurable remap pipeline** — `remap { }` step specs produce the
  production-namespace jar (`remapJar`), remapped dependencies, and
  sources jars.
- **Production runs** — `runProdClient`/`runProdServer` launch your mod
  against the Minecraft artifact it will actually link to at ship time.
- **Mod metadata generation** — `mod { }` emits `fabric.mod.json`,
  `mods.toml`, manifest attributes, and more, era-correct per loader.
- **Project init CLI** — `unimined init` scaffolds ready-made mod
  projects (see [unimined-cli](getting-started/cli.md)).
- **Adapters** — JvmDowngrader, Stonecutter, ExpectPlatform, Forgix,
  PatchBase, and ModPublisher integrate as `unimined.adapter-*` plugins.

## Supported Loaders

- [Fabric](https://fabricmc.net/), [Quilt](https://quiltmc.org/),
  [LegacyFabric](https://legacyfabric.net), Ornithe, and Babric — the
  whole intermediary family, alpha to current
- [Forge](https://minecraftforge.net/) — merged-jar FG1 era (≤1.6.4),
  userdev FG2 era (1.7.10–1.12.2), and modern installer-era, including
  LexForge and Cleanroom
- [NeoForge](https://neoforged.net/) — userdev-era and 26.x unobfuscated
- [Rift](https://github.com/DimensionalDevelopment/Rift),
  [LiteLoader](https://liteloader.com), ModLoader (Risugami), and plain
  jar-modding via [JarModAgent](https://github.com/unimined/JarModAgent)
- [Craftbukkit/Spigot](https://hub.spigotmc.org) and
  [Paper](https://papermc.io)
- Vanilla conversions — ReIndev, NSSS, Better-Than-Adventure, FoxLoader

### Planned

- Sponge
- NilLoader
- merged multi-loader environments
- ~~Flint Loader~~ (removed — maven.flintloader.net offline;
  reintroducible if the host returns)

### Custom Loaders

you can create a custom patching/transforming process for minecraft,
for example [PrcraftExampleMod](https://github.com/prcraft-minecraft/PrcraftExampleMod)'s buildsrc dir —
or register a loader descriptor, see [Loaders](guide/loaders.md).

## Getting started

- [Installation](getting-started/installation.md) and
  [First Project](getting-started/first-project.md) walk through a
  minimal build
- `unimined init` — the [CLI](getting-started/cli.md) generates a
  project with the right DSL for your version+loader
- Migrating a 1.x build? See [Migrating 1.x to 2.0](migration-1x-to-2.0.md)
