# Loaders

Each environment declares its loaders inside the env block — a named
verb like `fabric("0.16.0")`, or the generic
`loader("<name>", "<version>")` for ids without a verb (products,
custom descriptors). Multi-loader projects either use one env per
loader or repeat declarations on a single merged env where the extra
loaders are compile-only secondaries
([merged multi-loader](multi-project.md#merged-several-loaders-on-one-environment)):

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        minecraft {
            version = "1.20.1"
            fabric("0.16.0")

            sourceSet("forge") {
                version = "1.20.1"
                forge("47.2.0")
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        minecraft {
            version.set("1.20.1")
            fabric("0.16.0")

            sourceSet("forge") {
                version.set("1.20.1")
                forge("47.2.0")
            }
        }
    }
    ```

Loader names resolve through the loader registry:

--8<-- "generated/tables/loaders.md"

Each verb below is exactly `loader("<id>", "<version>")` — aliases are
accepted spellings that map to the same descriptor:

| Verb | Loader id | Aliases |
|---|---|---|
| `fabric(v)` | `fabric` | |
| `quilt(v)` | `quilt` | |
| `forge(v)` | `forge` | `minecraftForge` |
| `lexforge(v)` | `lexforge` | |
| `neoforge(v)` | `neoforge` | `neoForge`, `neoForged` |
| `cleanroom(v)` | `cleanroom` | |
| `legacyfabric(v)` | `legacyfabric` | `legacyFabric` |
| `babric(v)` | `babric` | |
| `ornithe(v)` | `ornithe` | `ornitheFabric` |
| `jarmod(v)` | `jarmod` | `jarMod` |
| `fg1(v)` | `fg1` | |
| `foxloader(v)`, `foxloader()` | `foxloader` | `foxLoader` |
| `liteloader(v)` | `liteloader` | |
| `rift(v)` | `rift` | |
| `spigot(v)` | `spigot` | |
| `paper(v)` | `paper` | |
| `craftbukkit(v)` | `craftbukkit` | `craftBukkit`, `bukkit` |

- **Spec-config form** — `fabric("0.16.0") { spec -> nestedJars { } }`
  declares the loader and configures the env's transform spec in one
  place (the Kotlin `Action` overload lives in
  `xyz.wagyourtail.unimined.transform`; Groovy's closure twin is a
  member — see [Remap pipeline](remap-pipeline.md)).
- **Version-omitting** — `foxloader()` resolves the newest release
  pairing with the dist; every other verb requires a version
  (`loader("id")` likewise).
- **Forge is era-aware** — `forge(…)` emits FG1-era wiring
  on ≤1.7.10, FML-style on 1.8–1.12, and modern Forge ≥1.13
  (scripts: [1.7.10](../generated/flat-forge-170.gradle.kts),
  [1.12.2](../generated/flat-forge-112.gradle.kts),
  [1.20.1](../generated/flat-forge-modern.gradle.kts) — the `.gradle`
  siblings are the Groovy twins).
- **Pseudo-loaders** — `jarmod` injects into the MC jar itself
  ([script](../generated/jarmod.gradle.kts)); `--jars split|merged`
  shapes the jar set ([split](../generated/jars-split.gradle.kts) /
  [merged](../generated/jars-merged.gradle.kts));
  `accessTransformer`/`accessWidener` are transform-only.
- **Products nest loaders** — `bta { loader("bta-babric", …) }`.
- **Single-arg form** — inside a *named* env, `loader("0.16.0")`
  reads the lone arg as the *version* and the env id as the loader
  name (`sourceSet("fabric") { loader("0.16.0") }`).
- **Bukkit-family dev plugins** — `craftbukkit`, `spigot`, and
  `paper` stage the dev jar into `run/<env>-<run>/plugins/` by
  default (`modDiscovery("plugins")`), so the server's own plugin
  loader discovers it exactly as production does — including
  `plugin.yml`/`paper-plugin.yml` validation and the real plugin
  classloader (required by `JavaPlugin`'s loader check on Paper).
  `modImplementation` plugin deps stage alongside and load for
  free. Opt back into classpath loading with
  `features { modDiscovery("classpath") }` — the
  ClasspathPluginLoader javaagent then scans source-set output
  dirs for `plugin.yml` instead (note: CPL cannot read
  `paper-plugin.yml`, so classpath mode only suits
  plugin.yml-era products).

Loader "api" artifacts resolve via `loaderApi` —
see [Dependencies](dependencies.md).

## Adding a loader to an existing project

The manual equivalent of `unimined-cli new --loader <name>=<ver>`:

1. **Declare the env** (a `sourceSet` env so it doesn't fight the
   existing `main` env — or on `main` for a single-loader project):

   === "Kotlin `build.gradle.kts`"
       ```kotlin
       unimined {
           minecraft {
               sourceSet("quilt") {
                   version = "1.20.1"
                   quilt("0.26.0")
               }
           }
       }
       ```

   === "Groovy `build.gradle`"
       ```groovy
       unimined {
           minecraft {
               sourceSet("quilt") {
                   version.set("1.20.1")
                   quilt("0.26.0")
               }
           }
       }
       ```

2. **Mappings** — the env's `mappings { }` chain (quilt:
   `quilt(build)` named layer — `intermediary` is contributed
   automatically; forge: `official()`/`mcp(...)`/`searge()`; see
   [Mappings](mappings.md)). Nested envs inherit the `main` env's
   declarations — override per env as needed.

3. **Metadata** — the env's `mod { }` block emits the loader's
   descriptor (`fabric.mod.json`, `META-INF/mods.toml`,
   `mcmod.info`, …): `id`, `name`, `version`, `depends` — see
   [Mod Metadata](metadata.md).

4. **Sources** — env code lives in `src/<env>/` (created with the
   source set); shared code stays in `main` — aux/sibling envs
   inherit the parent's classpath via `combineWith` or implicit
   parent inheritance.

5. **Runs** — `<env>Client`/`<env>Server` register automatically;
   aux flows use `datagen()`/`gametest()` (see
   [Datagen](datagen.md)/[Gametests](gametest.md)).

Generating fresh instead? `unimined-cli new mymod --loader quilt=0.26.0`
emits the same shape — `--modules` for the per-loader subproject
layout.
