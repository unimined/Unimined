# Mod Metadata

`mod { }` inside `unimined { }` is the mod identity + runtime
metadata, emitted into the loader's descriptor (fabric.mod.json,
mods.toml, …) at build time:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    --8<-- "generated/mod-metadata.gradle.kts"
    ```

=== "Groovy `build.gradle`"
    ```groovy
    --8<-- "generated/mod-metadata.gradle"
    ```
- `id`, `name`, `version`, `description`, `authors`, `license`, `url`, `icon`
- `depend(modId, versionRange)` — metadata deps
- `loader("fabric") { }` / `fabric { }` — loader-specific sections
  (entrypoints, extra keys)
- `mixins { create("name") { } }` — generated `*.mixins.json` configs
- `fmlModType("LANGPROVIDER")` inside a forge-family `loader` section —
  stamps the jar's `FMLModType` manifest attribute (`MOD`/`LIBRARY`/
  `GAMELIBRARY`/`LANGPROVIDER`/`CUSTOM`) for jarjar-layer
  classification; unset leaves FML's own locator discovery in charge

`--metadata dsl` on the CLI emits this block instead of hardcoding
values in the loader descriptor.

## Merging with hand-written files

If a resource already contains the loader's metadata file
(e.g. `src/fabric/resources/fabric/fabric.mod.json`), the generated
document is **merged** into it rather than colliding: objects merge
recursively, arrays union and deduplicate, and generated scalar
values win. Only the merged copy ships — the source file is excluded
from `processResources`. Non-JSON collisions still fail the build.

## Discovery

`generate<Env>ModMetadata` scans the environment's resources and
compiled classes at execution time:

- `*.mixins.json` files found in resources are referenced from the
  `mixins` array automatically (generated and hand-written configs
  union, deduplicated).
- Classes implementing the loader's gametest interface are added to
  the `fabric-gametest` entrypoint; `FabricClientGameTest`
  implementors land under `fabric-client-gametest`.
- Mixin classes are scanned for `@Mixin` targets × implemented
  interfaces; each pair becomes a `transitive-inject-interface`
  classTweaker rule (generated under
  `META-INF/unimined/injected-interfaces.ct`, referenced via
  `classTweakers`) and a `custom.loom:injected_interfaces` entry.
  Rules already present in the project's own CT/AW files are not
  re-emitted, and duplicates are suppressed.

Interface-injection names are emitted in the dev namespace; the
production repack rebases the metadata keys to the published
namespace. Dependent projects receive the injections through the
dependency-side classTweaker extraction.

Because generation needs compiled classes, it cannot feed the
project's own compile classpath — casts against injected interfaces
in first-party sources still need a manual rule. Dependents and the
runtime packaging are covered automatically.

## Manifest attributes

Manifest-attribute loaders emit a manifest body merged into the env
jar via `jar.manifest.from(...)`. The same body also lands in
generated resources (`META-INF/MANIFEST.MF`) so **dev runs see it** —
FML (modern `SecureJar` and legacy `CoreModManager` alike) parses
manifests inside classpath *directories*, which is how exploded dev
mods carry `FMLModType`/`MixinConfigs`/`FMLAT`. The file copy is
excluded from the packed jar; the attrs ride the real manifest.

Environment-fact attributes (`Fabric-Mapping-Namespace`,
`MixinConfigs`, `FMLAT`, loader-version stamps, …) describe the
environment, not your mod's identity, so they emit **even when
`mod { }` is unconfigured** — a hand-written metadata file stays
authoritative for the descriptor documents, but the manifest attrs
still reach the jar and the dev classpath.

Era-correct emission per forge-family loader:

| Loader / MC | Mixin configs | Access transformers |
|---|---|---|
| `forge`/`lexforge` (all) | `MixinConfigs` manifest attr — `mods.toml` has no `[[mixins]]` table | `META-INF/accesstransformer.cfg` auto-detected |
| `neoforge` ≥ 20.3 | `[[mixins]]` in `neoforge.mods.toml` (better errors) | same |
| `neoforge` < 20.3 | `MixinConfigs` manifest attr | same |
| `cleanroom` / legacy ≤1.12.2 | `MixinConfigs` manifest attr | canonical cfg auto-detected; non-canonical outputs additionally listed under `FMLAT` |

Loader-contributed extras merge verbatim: `environment.manifestAttributes`
(env channel — e.g. `manifestAttributes["FMLModType"] = "LANGPROVIDER"`
or ornithe's `Calamus-Generation`) plus emitter attrs
(`Fabric-Loader-Version`, …). A hand-written
`src/.../META-INF/MANIFEST.MF` folds its attributes into the generated
manifest — generated values win on conflict — rather than colliding.

## Datagen and gametest envs

`datagen()` / `gametest()` inside an environment declare auxiliary
source sets (`<env>Data`, `<env>Gametest`). They inherit the parent's
version/mappings and see its classes on their classpath, but emit **no**
metadata of their own — the parent's descriptor references their
classes (entrypoints, generated data). A second same-id metadata file
would collide on the classpath, so `generate<Env>ModMetadata` no-ops
for aux envs.

Server gametests run through the loader's JUnit artifact
(`fabric-loader-junit`) wired onto `testImplementation` by
`gametest()`. Client gametests additionally arm the env's automatic
`<env>Client` run with the loader's client-gametest JVM args (fabric:
`-Dfabric.client.gametest -Dfabric.client.gametest.modid=<project>`),
so `runFabricGametestClient` boots the client into the test thread
and exits — no manual run-config tweaks needed.
