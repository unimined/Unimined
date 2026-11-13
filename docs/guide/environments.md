# Environments

An *environment* is a Minecraft instance bound to a source set — the
unit that owns a product, loaders, mappings, run configs, and `mod*`
dependency configurations.

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        minecraft {
            // bound to main by default
            version = "1.20.1"
            loader("fabric", "0.16.0")
            // or a named source-set env:
            sourceSet("server") {
                loader("forge", "47.2.0")
                side = Side.SERVER
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        minecraft {
            // bound to main by default
            version.set("1.20.1")
            loader("fabric", "0.16.0")
            // or a named source-set env:
            sourceSet("server") {
                loader("forge", "47.2.0")
                side.set(Side.SERVER)
            }
        }
    }
    ```

- `sourceSet(name)` binds the env to a source set (default `main`);
  sibling envs are independent — `combineWith("other")` (optionally
  with `EnvironmentSection`s) composes one into another, and nested
  envs (`datagen()`/`gametest()`) inherit their parent implicitly.
- `side` selects `Side.CLIENT`/`SERVER`/`MERGED`
  ([example](../generated/side-server.gradle.kts) / [groovy](../generated/side-server.gradle)).
- Multiple envs in one project = flat multi-env;
  [multi-module](../guide/multi-project.md) is the alternative.

Envs are also the scope of `mod { }` metadata and `runs { }`.

## Environment knobs

Everything below lives inside an env block (`minecraft { … }` or
`sourceSet("x") { … }`):

- **Java** — `javaVersion` sets the compile/run floor (conventioned
  from the loader descriptor); `useToolchains` toggles Gradle
  toolchains for env-scoped compiles (conventioned from the
  extension-level `unimined.useToolchains`); `requiredJavaVersion`
  reads the effective requirement — version-json `javaVersion`
  raised by your `javaVersion` — and feeds toolchains, launchers,
  and tool-exec fallbacks.
- **Access widening** — `accessWideners` / `accessTransformers` are
  file collections of `*.accesswidener` / `accesstransformer.cfg`
  applied to the dev artifact; `transitiveAccessWideners` (default
  `true`) lets AW `transitive-*` directives from compile-classpath
  mods contribute — set `false` to isolate the dev jar.
- **Prod packaging** — `defaultRemapJar` (default `true`) auto-wires
  `remap<JarTask>` so the jar task's input gains a `-dev` classifier
  and the remap task produces the prod-namespace artifact;
  `apiOnly` tells bukkit-family transforms to compile against the
  loader's api artifact instead of building the patched server jar.
- **Merge controls** — `stripBundledServerLibraries` (default `true`)
  drops pre-1.13 server-jar entries that duplicate
  `minecraftLibraries` jars during the client+server merge;
  `mergeExcludeGlobs` adds entry globs to strip;
  `mergeIncludeGlobs` marks entries that are never stripped.
- **Library resolution** — `manifestUrls` adds ordered
  version-manifest locations searched after Mojang's;
  `libraryOverlays` merges `{name, url?, maxGameVersion?}` overlay
  lists into the version JSON; `librarySubstitutions` /
  `replaceLibraryVersion(group, name, classifier) { … }` rewrite
  matched library versions (regexes, `null` drops the library);
  `libraryFilter { dep -> … }` keeps a library only when every
  filter accepts it; `libraryCoordFilters` maps whole coords;
  `obfuscatedLibraries` flags coords that remap with the MC jar.
- `combinedSources` / `combinedOutputs` — file collections
  populated by `combineWith`; read-mostly, used by generated
  source jars and mod packaging.

## Minecraft artifact configurations

Each environment registers resolvable configurations producing the
Minecraft artifact at each mapping namespace — `minecraftRaw`,
`minecraftDev`, `minecraftProd` for `main`, `<env>MinecraftRaw`, …
for a named env. Resolve them like any configuration — e.g. custom
packaging tasks that need the prod artifact read
`configurations.named("minecraftProd")`.

| Configuration | Produces |
|---|---|
| `minecraftRaw` | MC in the *source* namespace — the artifact the game ships as (obfuscated for obf-era loaders, intermediary for fabric-family, mojmap for unobf'd releases) |
| `minecraftDev` | MC in the *dev* namespace your sources compile and dev runs launch against |
| `minecraftProd` | MC in the *production* namespace shipped mod jars are remapped to — the `remapJar` target. Feeds `runProd*` classpaths and mod-view remap analysis; collapses to the raw artifact when prod == source namespace |
| `minecraftLibraries` | the MC library set (version-json + loader libraries) |

`runProd*` resolves `minecraftProd` by default; loaders whose
production runtime remaps at launch override the view — FG2-era
forge (1.7.10–1.12.2) launches `minecraftRaw` (the shipped obf jar)
and lets `FMLDeobfTweaker` remap obf→searge at runtime, matching a
real installation.
