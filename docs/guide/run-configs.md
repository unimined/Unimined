# Run Configs

`runs { }` is top-level. Every environment gets auto-registered run
configurations, each materializing a `run<Name>` task plus IDE run
configurations from `genIdeRuns`.

## Auto-registered runs

| Environment | Runs |
|---|---|
| `main` | `client`, `server`, `prodClient`, `prodServer` |
| `<env>` | `<env>Client`, `<env>Server`, `<env>ProdClient`, `<env>ProdServer` |
| datagen envs | the env's own name (e.g. `data` → `runData`) |
| gametest envs | `<env>Client`/`<env>Server` plus `test`-task wiring |

The `prod*` variants launch against `minecraftProd` — Minecraft in
the namespace shipped mod jars are remapped to — with your `remapJar`
output and *direct* (un-remapped) mod dependencies: the
shipped-runtime check (1.x `runObfClient`/`runObfServer` parity).
FG2-era forge is the exception: real production launches the shipped
obf jar and lets `FMLDeobfTweaker` remap obf→searge at runtime, so
`prodClient`/`prodServer` there resolve `minecraftRaw` plus the
runtime-deobf flags instead of the pre-deobfed artifact.

## Reaching runs

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        runs {
            named("client") {           // creates if absent
                jvmArgs.add("-Xmx4G")
            }
            create("ciClient") {        // a custom variant
                prod.set(false)
            }
            runs.configureEach {        // every run
                environmentVariables.put("MC_DEBUG", "1")
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        runs {
            named("client") {           // creates if absent
                jvmArgs.add("-Xmx4G")
            }
            create("ciClient") {        // a custom variant
                prod.set(false)
            }
            runs.configureEach {        // every run
                environmentVariables.put("MC_DEBUG", "1")
            }
        }
    }
    ```

Tasks are `run<Name>` — `gradle runClient`, `runProdServer`,
`runFabricData`, …

## RunConfig fields

| Field | Purpose |
|---|---|
| `environment` | which env's classpath/dirs the run launches (convention: the env it was registered for) |
| `enabled` | `false` = the `run<Name>` task exists but fails with a "disabled" message — gate runs on env/CI conditions |
| `beforeRun(task)` / `beforeRun { }` | task deps/actions wired ahead of launch (e.g. build a datapack first) |
| `side` | `CLIENT`/`SERVER` — defaults from the run name |
| `prod` | `true` = production-namespace MC artifact + remapped jar |
| `mainClass` | launch main class (loader-provided by default) |
| `jvmArgs` / `programArgs` | launch arguments |
| `javaagents` | file collection rendered as `-javaagent:` flags (unimined agents attach here — jarmod, expectplatform, CPL) |
| `environmentVariables` | process env |
| `stdin` | piped into the launched process |
| `features` | version-json rule features (`is_demo_user`, `has_custom_resolution`) |
| `width`/`height` | `--width`/`--height` args |
| `downloadAssets` | fetch assets before launching (default true) |
| `username` | offline-name shorthand bridging into `auth.username` |
| `auth { }` | typed session spec — see [Auth](auth.md) |

## Argument substitution

Loader capability templates and your own args support `${...}`
launch-property substitution (game_directory, auth_*, classpath, …)
plus `{modid}` — resolved against `mod { id }` at run time (e.g.
fabric gametest's `-Dfabric.client.gametest.modid={modid}`).

## Dev-time manifest attributes

Dev runs launch *exploded* output dirs, not a jar — but FML (modern
`SecureJar` and legacy `CoreModManager`) parses `META-INF/MANIFEST.MF`
inside classpath **directories**, so manifest attrs still apply.
`generate<Env>ModMetadata` writes the emitted manifest body into
generated resources (`build/generated/unimined/mod/<env>`), which the
source set packs into the resources output — `runClient`/`runServer`
classify the env's output with `FMLModType`, `MixinConfigs`, `FMLAT`,
`TweakClass`, … exactly like the built jar. To test a non-`MOD`
classification in dev, set `mod { loader("forge") { fmlModType("LANGPROVIDER") } }`
— the attribute reaches both the dev classpath and the shipped jar
from one declaration.

Loader dev-mode flags are wired per-loader: fabric-family runs get
`-Dloader.development=true`/`fabric.development` (and quilt's
`loader.development`) from the env wiring — no run-config edits
needed. Anything else is ordinary `jvmArgs`/`environmentVariables`.

## Dry-run

`-Punimined.run.dryRun` prints the assembled launch (main class,
args, classpath) instead of exec'ing — the quickest way to inspect
what a run would do:

```bash
./gradlew runClient -Punimined.run.dryRun
```

## IDE integration

`genIdeRuns` emits per-env IntelliJ `.idea` run XML and Eclipse
`.launch` files for every declared run — regenerate after editing
`runs { }`.

## Auth

See [Auth](auth.md) — `auth { microsoft() }` per run or
`runs { auth { } }` as the default; `:loginMicrosoft` populates the
credential store; `unimined.auth.*` properties control storage.
