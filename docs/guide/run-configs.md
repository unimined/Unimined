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

Forge-family loaders (forge, lexforge, neoforge, cleanroom) also
declare the userdev run types — `data`, `clientData`, `serverData`,
`gameTestServer`, `junit` — which materialize as `runData`,
`runGameTestServer`, … (`<env>Data` on non-main envs) with no
`runs.create` needed. A run type the resolved profile doesn't
declare launches the plain environment launch.

The `prod*` variants launch against `minecraftProd` — Minecraft in
the namespace shipped mod jars are remapped to — with your `remapJar`
output and *direct* (un-remapped) mod dependencies: the
shipped-runtime check (1.x `runObfClient`/`runObfServer` parity).
Loader-runtime content rides the env's `minecraftLibraries` channel
onto both dev and prod classpaths — the fabric-family loader jar, its
embedded libraries, dep-declared installer libraries, and the
relabeled `mappings/mappings.tiny` jar the loader's
`MappingConfiguration` resolves the runtime namespace through.
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
            configFirst("client") {     // applies before other config
                jvmArgs.add("-Xms1G")   // (declare it first)
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
            configFirst("client") {     // applies before other config
                jvmArgs.add("-Xms1G")   // (declare it first)
            }
        }
    }
    ```

`configFirst` applies its action ahead of `named`/`create`/
`configureEach`/`all` configuration of the same run — the 1.x hook
for laying down values later blocks can override (plugins use it for
defaults). Declare it before other configuration of the run: a
`configFirst` issued after the run already configured applies
immediately — after whatever already ran.

Tasks are `run<Name>` — `gradle runClient`, `runProdServer`,
`runFabricData`, …

## RunConfig fields

| Field | Purpose |
|---|---|
| `environment` | which env's classpath/dirs the run launches (convention: the env it was registered for) |
| `enabled` | `false` = the `run<Name>` task exists but fails with a "disabled" message — gate runs on env/CI conditions |
| `beforeRun(task)` / `beforeRun { }` | task deps/actions wired ahead of launch (e.g. build a datapack first) |
| `side` | `CLIENT`/`SERVER` — defaults from the run name |
| `type` | loader run-config key (`runs.<type>` in forge userdev profiles) — defaults to the run name; set `type.set("data")` on a renamed run to bind the `data` launch shape |
| `prod` | `true` = production-namespace MC artifact + remapped jar |
| `mainClass` | launch main class (loader-provided by default) |
| `jvmArgs` / `programArgs` | launch arguments |
| `javaagents` | file collection rendered as `-javaagent:` flags (unimined agents attach here — jarmod, expectplatform, CPL) |
| `environmentVariables` | process env |
| `systemProperties` | `-D` JVM system properties — typed alternative to raw `jvmArgs` `-D` strings; rendered ahead of `jvmArgs` so an explicit `-D` there still wins |
| `launchProperties` / `setProperty(k, v)` | `${key}` substitution values applied to args/jvmArgs/env at launch — `setProperty("user", "Dev")` then `programArgs.add("--username=\${user}")`. Lazy forms: `Provider`, `() -> String`, and a Groovy closure; user keys win over env defaults and loader keys |
| `logLevel` / `loggingConfigFile` | root log level for a generated log4j2 dev config (`-Dlog4j.configurationFile` is added automatically), or an explicit config file that overrides it |
| `additionalRuntimeClasspath` | per-run classpath additions — files appended to this run's launch only; maven coords go on the `run<Name>Dependencies` configuration |
| `stdin` | piped into the launched process |
| `features` | version-json rule features (`is_demo_user`, `has_custom_resolution`) |
| `width`/`height` | `--width`/`--height` args |
| `downloadAssets` | fetch assets before launching (default true) |
| `username` | offline-name shorthand bridging into `auth.username` |
| `auth { }` | typed session spec — see [Auth](auth.md) |
| `ideName` / `ideFolderName` | IDE run-configuration display/file name (defaults to the run name) and an optional folder/group label |
| `ideConfigGenerated` | `false` suppresses this run's IDE run-configuration emission — `genIdeRuns` skips it |
| `singleInstance` | `true` marks the emitted IntelliJ configuration "single instance only" (`singleton="true"` — IDEA refuses a second concurrent launch); IDEA emitter only, no effect on `run<Name>` execution |
| `unitTest` | marks the run as a JUnit test run — a Gradle `Test` task (`test<Name>`) materializes alongside `run<Name>`; conventions to `true` for `junit`-typed runs on forge-family loaders (`gametestJunit` capability). The loader's program args ride an args-file in `fml.junit.argsfile`, jvm args an `@argfile` |
| `unitTestSources` | source sets supplying test classes + classpath for a `unitTest` run — defaults to `test` |
| `loadedMods` | which of the environment's `mods { "<id>" { } }` groups this run loads — `loadedMods("a", "b")` selects a subset; empty (default) loads all declared groups. Selected groups join the launch classpath and serialize into the loader's mod-grouping channel (`fabric.classPathGroups` / `MOD_CLASSES`) |
| `modFolderFiles` | extra mod jars staged verbatim into the run's mod folder by `prepare<Name>` — loaders declaring the `modsDir` capability copy them to `runDir/<dir>` (`mods/` on forge/conversion-family, `plugins/` on bukkit). Never remapped and never on the launch classpath — the loader's folder discovery loads them. For jars that must run under the loader's classloader (e.g. a ModLauncher `ITransformationService` carrier like mixin-engine coremods) rather than `-cp`. On classpath-discovery loaders this is the only staged content — the project jar and `*Direct` views already register through `ClasspathLocator`/`MOD_CLASSES` and a folder copy would double-register them |

## Argument substitution

Loader capability templates and your own args support `${...}`
launch-property substitution (game_directory, auth_*, classpath, …)
plus `{modid}` — resolved against `mod { id }` at run time (e.g.
fabric gametest's `-Dfabric.client.gametest.modid={modid}`). Declare
your own keys with `setProperty` — they resolve lazily at launch and
override the built-in/loader-supplied values for that run.

## Dev-time manifest attributes

Dev runs launch *exploded* output dirs, not a jar — but FML (modern
`SecureJar` and legacy `CoreModManager`) parses `META-INF/MANIFEST.MF`
inside classpath **directories**, so manifest attrs still apply.
`gen<Env>ModMetadata` writes the emitted manifest body into
generated resources (`build/generated/unimined/mod/<env>`), which the
source set packs into the resources output — `runClient`/`runServer`
classify the env's output with `FMLModType`, `MixinConfigs`, `FMLAT`,
`TweakClass`, … exactly like the built jar. To test a non-`MOD`
classification in dev, set `mod { loader("forge") { fmlModType("LANGPROVIDER") } }`
— the attribute reaches both the dev classpath and the shipped jar
from one declaration.

Loader dev-mode flags are wired per-loader: fabric-family runs get
`-Dloader.development=true`/`fabric.development` (and quilt's
`loader.development`) from the env wiring; FoxLoader 1.x conversion
runs get `-Dfoxloader.dev-mode=true` because their Minecraft artifact
is pre-patched at build time — no run-config edits needed. Anything
else is ordinary `jvmArgs`/`environmentVariables`.

## Dry-run

`-Punimined.run.dryRun=true` prints the assembled launch (main class,
args, classpath) instead of exec'ing — the quickest way to inspect
what a run would do:

```bash
./gradlew runClient -Punimined.run.dryRun=true
```

## Verifying a launch

`-Punimined.run.verify=true` audits the assembled launch plan without
starting a JVM and fails the task on violations a real launch would
hit at class-load:

```bash
./gradlew runFabricProdClient -Punimined.run.verify=true
```

Checks: every classpath entry exists on disk, the resolved main class
is present on the classpath, loader-required resources are present
(`mappings/mappings.tiny` when `fabric.gameMappingNamespace` is set —
the fabric-family prod-namespace flag), javaagent jars exist, and the
asset index is on disk. An empty plan prints an `OK` line; violations
list as `FAIL` entries and fail the task, so it doubles as a CI gate.

### Interactive pass/fail survey

`-Punimined.verify=true` is the human-verification counterpart to the
static audit above: the game really launches, and when its process exits
the run task prompts on the controlling terminal for `pass`, `fail`, or
`skip` (the default is `pass` after a clean exit, `fail` after a nonzero
one). Each run appends a row to `verification-results.md` in the project
directory; a failure verdict can carry a one-line note.

```bash
./gradlew runClient -Punimined.verify=true
```

The verdict is the task outcome: `fail` fails the task even when the
game exited cleanly, while `pass` forgives a nonzero exit (useful for
window-close quirks). `skip` and rows recorded as `unverified` — the
fallback when no controlling terminal is available — defer to the
process exit code. This is a manual matrix-pass harness, not a CI gate.

## Launch scripts

`genLaunchScript<Name>` resolves the same launch as `run<Name>` but
writes runnable `run<Name>.sh` / `run<Name>.cmd` into the run's
working directory (`run/<env>-<name>/`) instead of exec'ing — for
launching outside Gradle, e.g. under a debugger or another terminal.
`genLaunchScripts` (plural) emits them for every declared run:

```bash
./gradlew genLaunchScriptClient
# or everything:
./gradlew genLaunchScripts
./run/main-client/runClient.sh
```

The scripts `cd` into their own directory, export the launch
environment, and exec `java` with the fully resolved args/classpath —
the same values `run<Name>` would use.

## Listing runs

`uniminedRuns` prints one line per declared run config (task, env,
side, type, prod flag, enabled, main class) — a quick check of what
`runs { }` materialized:

```bash
./gradlew uniminedRuns
```

## Cache maintenance

`pruneUniminedCache` prunes the global unimined cache
(`~/.gradle/caches/unimined`), deleting entries idle longer than 30
days by default — `--age-days 0` removes everything, `--project`
additionally wipes the project's `.gradle/unimined`, `--assets`
includes the shared assets dir.

## IDE integration

`genIdeRuns` emits per-env IntelliJ `.idea` run XML and Eclipse
`.launch` files for every declared run — regenerate after editing
`runs { }`.

## Auth

See [Auth](auth.md) — `auth { microsoft() }` per run or
`runs { auth { } }` as the default; `:loginMicrosoft` populates the
credential store; `unimined.auth.*` properties control storage.
