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

The `prod*` variants launch the *production-namespace* Minecraft
artifact with your remapped mod jar — the shipped-runtime check
(1.x `runObfClient`/`runObfServer` parity).

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
