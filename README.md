# Unimined

Unified Minecraft modding environment with support for legacy environments.

for details on usage, see [the docs](https://wagyourtail.github.io/unimined/docs/latest/)
(sources in [`docs/`](docs/index.md))

> **Note:** the **2.0 modular rewrite** is out as a **prerelease** —
> the monolithic plugin is now small composable plugins
> (`unimined-api`, `unimined-remap`, `unimined-minecraft`,
> `unimined-transform`, `unimined-runs`, `unimined-source`, and the
> `unimined` frontend). Loader support is *preliminary* — expect some
> jank while it beds in. The 1.x codebase lives on the `lts/*`
> branches; 1.x users should see
> [the migration guide](https://wagyourtail.github.io/unimined/docs/latest/migration-1x-to-2.0/).

## Requirements

- **Gradle daemon heap: 4 GB.** Remapping the full Minecraft jar (and
  mod-dependency artifacts) currently runs inside the Gradle daemon via
  artifact transforms — the default daemon heap will `OutOfMemoryError`.
  Set it in the consuming project's `gradle.properties`:

  ```properties
  org.gradle.jvmargs=-Xmx4g
  ```

  This is a transitional constraint: the 2.0 plan moves heavy remap work
  into `WorkerExecutor` process isolation with explicit worker heaps, at
  which point the daemon requirement goes away.

## LTS Branch

In order to better support buildscripts. when I plan on making big/breaking changes, I will leave an LTS branch behind.
In effect, this means that I always support the previous minor version for bugfixes.

By consequence, seeing as it's only bugfixes, snapshots on the LTS branch are considered *relatively* stable as well.

## Supported Loaders

- [Fabric](https://fabricmc.net)
- [Quilt](https://quiltmc.org)
- [Legacy Fabric](https://legacyfabric.net)
- [Babric](https://babric.github.io)
- [Ornithe](https://ornithemc.net)
- [Minecraft Forge](https://minecraftforge.net)
- [Neoforge](https://neoforged.net)
- [Cleanroom](https://cleanroommc.com)
- ~~Flint Loader~~ (removed — maven.flintloader.net offline)
- [JarModAgent](https://github.com/unimined/JarModAgent)
- [Rift](https://github.com/DimensionalDevelopment/Rift)
- [FoxLoader](https://github.com/Fox2Code/FoxLoader)
- [LiteLoader](https://liteloader.com)
- [CraftBukkit](https://bukkit.org)
- [Spigot](https://www.spigotmc.org)
- [Paper](https://papermc.io)
- Risugami's ModLoader
- just plain jarmodding

## Planned Loaders

- [Sponge](https://spongepowered.org/]Sponge)
- [NilLoader](https://git.sleeping.town/Nil/NilLoader)

## Custom Loaders

Yes, this is possible, see [PrcraftExampleMod](https://github.com/prcraft-minecraft/PrcraftExampleMod) and it's buildsrc
dir.

## Supported Games / Total Conversion Mods

- [Minecraft](https://minecraft.net)
- [Minecraft: ReIndev](https://reindev.miraheze.org/wiki/Reindev_Wiki)
- [Minecraft: Better Than Adventure](https://betterthanadventure.net)

## Planned Games / Total Conversion Mods

- [Not So Seecret Saturday](https://www.notsoseecretsaturday.net)

## TODO

- fix yarn on neoforge (these will probably be agents, possibly in separate projects and pulled like JarModAgent)
    * inject remapper into ASMAPI
    * reflection remapper (also for forge potentially, or in general).
- genSources should apply forge source patches (at least on fg3+)

## Recommended Setup

- `unimined new <name>` with the [CLI](docs/reference/cli.md), or
- grab a starter from [Unimined-Templates](https://github.com/unimined/Unimined-Templates), or
- add `id("xyz.wagyourtail.unimined") version "2.0.0"` to your `plugins { }` block — see
  [the getting-started guide](https://wagyourtail.github.io/unimined/docs/latest/)

## Other Setups

### Arch-Loom Style

- direct porting of arch-loom projects without changing the directory structure is possible.
- instructions pending...

### third party template (s)

- *(out of date — check back later)*
