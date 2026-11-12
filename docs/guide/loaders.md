# Loaders

One loader per environment; multi-loader means one env per loader.
Declare it inside the env block — `loader("<name>", "<version>")`:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        minecraft {
            version = "1.20.1"
            loader("fabric", "0.16.0")

            sourceSet("forge") {
                version = "1.20.1"
                loader("forge", "47.2.0")
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        minecraft {
            version.set("1.20.1")
            loader("fabric", "0.16.0")

            sourceSet("forge") {
                version.set("1.20.1")
                loader("forge", "47.2.0")
            }
        }
    }
    ```

Loader names resolve through the loader registry:

--8<-- "generated/tables/loaders.md"

- **Forge is era-aware** — `loader("forge", …)` emits FG1-era wiring
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
                   loader("quilt", "0.26.0")
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
                   loader("quilt", "0.26.0")
               }
           }
       }
       ```

2. **Mappings** — the env's `mappings { }` chain (quilt:
   `quiltMappings(...)`/`hashed()`; forge: `official()`/`mcp(...)`/
   `searge()`; see [Mappings](mappings.md)). Nested envs inherit the
   `main` env's declarations — override per env as needed.

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
