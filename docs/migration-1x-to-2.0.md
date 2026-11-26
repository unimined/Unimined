# Migrating from 1.x to 2.0

Unimined 2.0 splits the monolithic `unimined { }` extension into
per-domain plugins with their own extensions. Groovy scripts that
call removed 1.x verbs now get a migration note naming the new
form; Kotlin DSL scripts fail at compile time — this table maps
old calls to their 2.0 equivalents.

## What's new in 2.0

Not just moves — the rewrite lands a wave of loader-parity
features you'll hit while porting (each links to its guide page):

**Environments & products**

- **Compile-time client split** — `splitEnvironmentSourceSets()`
  turns `main` into a common (client∩server) compile env and binds
  an aux `client` env; client runs redirect automatically
  ([Environments](guide/environments.md#split-environment-source-sets)).
- **Merged multi-loader envs** — repeat `loader(name, version)` in
  one env: the first is the primary (prod/remap/runs), the rest are
  compile-classpath secondaries — the 1.x `merged { }` analog
  ([Multi-project](guide/multi-project.md#merged-several-loaders-on-one-environment)).
- **Declared-source dists** — `legacydist { file/maven/url/producer }`
  for jars with no manifest provider; `bta`/`reindev`/`nsss`/`custom`
  dist presets on `unimined { }` or any env
  ([Products](guide/products.md#declared-source-dists-legacydist)).
- **Dev bundles** — `devbundle { file(...) }` consumes paperweight
  zips; `devBundleExport { }` + `export<Env>DevBundle` *produces*
  a self-contained bundle of the env's prod artifact
  ([Products](guide/products.md#exporting-a-dev-bundle)).

**Aux envs & mod groups**

- **`datagen()` / `gametest()`** — auxiliary source-set envs that
  inherit the parent's sections; datagen materializes a `run<Data>`
  config, gametest wires the loader's JUnit artifact + `test` task
  ([Datagen](guide/datagen.md), [Gametests](guide/gametest.md)).
- **`mods { }` classpath groups** — name which source-set outputs
  make up each mod on a dev launch; serializes onto
  `fabric.classPathGroups` / `MOD_CLASSES`, subset via
  `run.loadedMods` ([Environments](guide/environments.md#mod-classpath-groups)).

**Runs**

- **`runProdClient`/`runProdServer`** — launch the prod-namespace
  artifact + remapped jar (1.x `runObf*` parity)
  ([Run configs](guide/run-configs.md)).
- **Launch scripts** — `genLaunchScript<Name>`/`genLaunchScripts`
  write runnable `run<Name>.sh`/`.cmd` into the run dir
  ([Run configs](guide/run-configs.md#launch-scripts)).
- **`uniminedRuns` report** — one line per declared run (task,
  env, side, type, main class)
  ([Run configs](guide/run-configs.md#listing-runs)); plus
  `-Punimined.run.dryRun=true` to print an assembled launch without
  exec'ing.
- **Run surface fill-out** — `systemProperties`, `logLevel`/
  `loggingConfigFile`, `additionalRuntimeClasspath` + the
  `run<Name>Dependencies` bucket, `type` (bind a run to a loader's
  `data`/`junit`/`gameTestServer` launch shape), `unitTest` +
  `test<Name>` JUnit twins, `from()` run inheritance, `ideName`/
  `ideFolderName`/`ideConfigGenerated`, `singleInstance` (IDEA
  singleton configs) ([Run configs](guide/run-configs.md#runconfig-fields)).

**Mappings, remap & access**

- **UML-backed mapping pipeline** — one `mappings { }` chain per
  env; channels span `yarn`/`yarnv1`, `quilt`, `mcp`, `retroMCP`,
  `searge`, `mojmap`, `feather`/`calamus`/`legacyIntermediary`/
  `babricIntermediary`, `legacyYarn`/`barn`/`biny`/`nostalgia`,
  `unknownThingy`, and Ornithe enrichers
  (`nests`/`sparrow`/`raven`, auto-contributed on Ornithe envs),
  `parchment`, `spigot`, `forgeBuiltinMCP`, inline `stubs`, and the
  `builtin(name)` escape hatch ([Mappings](guide/mappings.md)).
- **`hierarchyNameFill`** — tri-state bytecode hierarchy fill that
  propagates mapped member names to unmapped overrides (auto-on
  with Ornithe enrichers); `customIntermediaries` suppresses
  loader-contributed intermediary hops — both live on `mappings { }`
  as `Property<Boolean>`s: `.set(true)` in Kotlin, `= true` still
  works in Groovy
  ([Mappings](guide/mappings.md#bytecode-hierarchy-fill)).
- **`remap { }` step pipeline** — typed step specs per surface
  (`remap`, `remapAccess`, `modArtifacts`, plus engine verbs
  `unpick`/`nester`/`raven`/`sparrow`/`condor`/`preen`, `jst`,
  `sourceRemap`, `applyClassTweaker`, `downgrade`,
  `expectPlatform`); `remappedViews` remaps arbitrary dependency
  buckets ([Remap pipeline](guide/remap-pipeline.md)).
- **Unified `access { }` DSL** — semantic AW/AT/CT declarations
  (`accessible`/`extendable`/`mutable` + `transitive-*` twins,
  `injectInterface`, `extendEnum`, `at`, file `from(...)`,
  `fileName`/`format`/`validate`) emitted in the loader's consumed
  format; `validate<Env>Access` checks declared targets without a
  remap ([Environments](guide/environments.md#access-declarations)).

**Packaging & tooling**

- **`mod { }` metadata** — era-correct emitters per loader
  (`fabric.mod.json`, `quilt.mod.json`, `mods.toml`, `mcmod.info`,
  manifest attrs); `include` nested jars in `fabric` or `jarjar`
  layout with `fmlModType` marking and deterministic recompress
  ([Mod Metadata](guide/metadata.md),
  [Dependencies](guide/dependencies.md#jar-in-jar-include)).
- **`uniminedTools { }`** — override the build-time tool
  coordinates (JST, unprotect, mcpcleanup, DiffPatch, jvmdg,
  classpath-plugin-loader, stitch)
  ([Dependencies](guide/dependencies.md#tool-coordinates)).
- **Repo management** — loader mavens auto-register per env;
  `uniminedRepos.<name>.ensure()` adds a known repo by name, and a
  post-DSL diagnostic warns on missing/misordered repos
  ([Dependencies](guide/dependencies.md#repositories)).
- **Sources & IDE** — `gen<Env>Sources`/`gen<Env>ModSources`
  (external engines via `source { generator(dep) }`), opt-in
  `cleanup<Env>Sources` decompile-cleanup fixups,
  `genIdeRuns`/`gen<Env>IdeaRuns`/`gen<Env>EclipseRuns` with stable
  argfile indirection, and `pruneUniminedCache` for the shared
  cache ([Sources](guide/sources.md),
  [Run configs](guide/run-configs.md#cache-maintenance)).
- **`enabled` convention** — toggles are `Property<Boolean>`s with
  an env > inherited > global > default precedence;
  `runs { enabled.set(false) }` disables every run task, per-run
  `enabled` gates one — new opt-outs follow this pattern (the
  legacy `disableLvtPatch()`/`disablePreen()`/`disableNests()`
  verbs stay for compat).

## Environment configuration

| 1.x | 2.0 |
|---|---|
| `unimined.minecraft(sourceSet) { }` | `unimined.minecraft { }` + per-source-set env (`minecraft { sourceSet("main") { } }`) |
| `unimined.reIndev(sourceSet) { }` | `unimined.minecraft { reindev { } }` (dist preset on `main`) or per-env `sourceSet("x") { reindev { } }` |
| `unimined.bta(sourceSet) { }` | `unimined.minecraft { bta { } }` or per-env `bta { }` — same for `nsss { }`/`custom("name") { }` |

Loader declaration moves onto the environment. Every 1.x
`env.<patcher> { }` block — `fabric`, `quilt`, `forge`/
`minecraftForge`, `neoForge`/`neoForged`/`neoforge`, `cleanroom`,
`legacyFabric`, `babric`, `ornitheFabric`/`ornithe`, `jarMod`,
`foxLoader`, `liteloader`, `rift`, `spigot`, `paper`,
`bukkit`/`craftBukkit` — is now a named **version-arg verb**:
`fabric("0.16.0")`, `forge("47.2.0")`, `neoForge("21.0.0")`, … Each
verb is exactly `loader("<id>", "<version>")` under the hood, and the
generic form stays available for ids without a verb
(`bukkit`/`craftBukkit` → `craftbukkit(…)` → `craftbukkit(…)`;
see the [verb table](guide/loaders.md)). Spec-config still rides
the trailing block — `fabric("0.16.0") { spec -> … }` delegates to
the transform spec, not a 1.x patcher object.

Special cases:

- **`merged { fabric; forge }`** — gone as a block; repeat loader
  declarations in one env instead. The **first** declaration is
  the primary (owns prod namespace, remap, metadata, runs); later
  ones are compile-only secondaries
  ([Multi-project](guide/multi-project.md#merged-several-loaders-on-one-environment)).
- **`modloader`/`risugami` (Risugami's ModLoader)** — ModLoader is
  not a loader in 2.0; it's a jar-mod dependency. Declare
  `jarMod("<agentVersion>")` and put
  `jarMod("risugami:modloader:<mcVersion>")` on the env — the
  `jarmod-modloader-fixes` step patches `ModLoader.class` in dev
  automatically. `loader("modloader")`/`loader("risugami")` fail
  with *unknown loader*.
- **`nil` (NilLoader), `folia`, `arch`** — no 2.0 loader
  descriptor exists yet; `loader("<id>")` for these fails with
  *unknown loader*. NilLoader is on the planned list.
- **JarMod era extras** — `env.jarmod("x")`/`env.jarMod("x")` /
  `env.fg1("x")` declare the loader; `env.customSearge()` toggles
  the user-supplied searge/mcp chain (requires the
  `transform.jarmod` plugin).
- **`flint { }`** — removed entirely: maven.flintloader.net is
  offline so `net.flintloader:*` artifacts are unresolvable. The
  descriptor can return via `LoaderRegistry` + a mirrored repo if
  the host comes back.

Kept verbatim on the environment: `customGameProvider` (suppresses
the loader's built-in MC game-provider lookup for total-conversion
setups — emits the descriptor's `skipMcProvider` system property;
typically paired with an `empty-*` version, which still parses as a
resolve-nothing pseudo-version).

Other env-level 1.x forms:

| 1.x | 2.0 |
|---|---|
| `env.accessWidener(path)` / `env.accessTransformer(path)` | `access { from("path") }`, or the `accessWideners`/`accessTransformers` file collections (`accessWideners.from(...)`) |
| `env.version "1.20.1"` / `env.side "joined"` (call form) | `version = "1.20.1"` / `version.set(...)`; `side = Side.MERGED` / `side.set(...)` — both are `Property`s now (Groovy `=` assignment still bridges, incl. `joined`/`combined` → `MERGED`) |
| `env.runs { }` nested inside an env | `runs { }` is top level (`runs { }` / `unimined.runs { }`), not per-env |
| `env.source { }` / `env.generator(dep)` nested inside an env | `unimined.source { generator(dep) }` — decompile-engine config is global |
| `env.remap(...)` / `env.remapSources(...)` task verbs | auto-wired `remap<Env>Jar` / `remap<Env>SourcesJar` production tasks; custom artifact remaps go through `unimined.remap { }` surfaces |
| `env.obfuscated = true` | read-only now — `env.obfuscated` is a `Provider<Boolean>` the provider resolves (unobfuscated MC builds come back `false`); do not assign |
| `env.defaultRemapJar = false` | kept as `Property<Boolean>` — `env.defaultRemapJar.set(false)` disables the auto `remap<Env>Jar`/prod-sources wiring (Groovy `=` assignment is rejected — `.set` only) |
| `env.defaultRemapSourcesJar` / `env.createJarTask` | gone — `defaultRemapJar` gates the whole prod jar+sources wiring; there is no per-artifact opt-out |
| `env.canCombine` | removed — merge semantics are fixed; declare merges via `combineWith`/`from` |
| `env.useUnionRelaunch` / `env.onMergeFail` | removed — no equivalents |
| provider internals (`mcPatcher`, `minecraftRemapper`, `tinyRemapperConf`, `minecraftData`, `minecraftDependency`, `minecraftFileDev`, `minecraftSourceFileDev`, `mergedOfficialMinecraftFile`, `forgeMCVersion`, `isMinecraftJar`, `combinedWithList`, `localCache`) | removed — no DSL equivalent |

## Remap pipeline

| 1.x | 2.0 |
|---|---|
| remap hooks inside `unimined { }` | `unimined.remap { }` — step verbs (`unpick(...)`, `applyClassTweaker {}`, `sourceRemap {}`) per surface, both Groovy and Kotlin. Source remapping runs through UML's source remapper by default; `jst {}` remains as an explicit engine opt-in (verification/alternate engine), not a peer default |
| `unimined.migrateMappings(sourceSet) { }` | `unimined.migrateMappings("main") { }` (env id), or `unimined.migrate { }` |
| surface `devNamespace`/`prodNamespace`/`devFallbackNamespace` | namespaces come from the environment's declarations — `devNamespace`, `devRemapSourceNamespace`, `modSourceNamespaces` on the env, plus the `remap { }` step's output |
| `remapAccessWidener` / `remapAccessTransformer` / `remapATToLegacy` | `remapAccess { }` step on the surface |
| tiny-remapper callbacks — `config`, `tinyRemapperConf`, `remapper(TinyRemapper.Builder)`, `addResourceRemapper`, `addExtension` | engine knobs live on the `remap { }`/engine step specs (`jsr305ToJetbrains` covers `replaceJSRWithJetbrains`, on by default) |
| `allowImplicitWildcards` | removed — no equivalent; open an issue if a workflow depended on it |

## Repositories

The ~25 `unimined.*Maven()`/`unimined.*Repos()` helpers
(`fabricMaven`, `minecraftForgeMaven`, `curseMaven`, `jitpack`,
`spigot`, `quiltMaven`, `ornitheMaven`, `legacyFabricMaven`,
`neoForgedMaven`, `cleanroomRepos`, `modrinthMaven`, `glassLauncherMaven`,
`parchmentMaven`, `babricMaven`, …) are gone: loader repositories
are **auto-registered per environment** in 2.0. Remove the calls;
use a normal `repositories {}` block for anything custom.
`modsRemapRepo` is likewise managed internally.

If a script just wanted to ensure a known repo by name, the
`uniminedRepos` catalog does it (`uniminedRepos.fabric.ensure()`);
a post-DSL diagnostic warns when an ensured repo goes missing or
`Mojang Libraries` ends up ahead of `MavenRepo`
([Dependencies](guide/dependencies.md#repositories)).

## Other moves

| 1.x | 2.0 |
|---|---|
| mod/transform config inside `unimined { }` | `unimined.mod { }`, `unimined.transform { }` (`unimined.mods { }` → top-level `mod { }`) |
| global toolchain toggle | `unimined.useToolchains = false` |
| env java override | `unimined.javaVersion` (delegates to the `main` env) |
| `jvmArgs += "-javaagent:/path/agent.jar"` | `javaagents.from(file("agent.jar"))` on the run config — a typed file collection resolved to `-javaagent:` flags at launch |

Unknown or removed verbs raise a `GradleException` naming the
replacement — read the message; it points here. Verbs contributed
by optional plugins (`jarmod`, `patchBase`, `jvmdowngrader`,
`devBundleExport`, `transform`, remap engine steps) name the plugin
to apply when it isn't.

### Task names

2.0 standardizes per-environment tasks on `verb<Env>Noun` — the env
id sits between the verb and the object, and the `main` environment
drops it (`genSources`, `remapJar`, `validateAccess`). Every renamed
task keeps its old name as a deprecated alias that warns and
delegates, so existing scripts/CI keep working:

| pre-convention name | canonical name |
|---|---|
| `applySourcePatch<Env>`, `createSourcePatch<Env>`, `createClassPatch<Env>` | `apply<Env>SourcePatch`, `create<Env>SourcePatch`, `create<Env>ClassPatch` |
| `recompileMc<Env>[Classes]` | `recompile<Env>Mc[Classes]` |
| `forgeSources<Env>` | `gen<Env>ForgeSources` |
| `genIdeaRuns<Env>`, `genEclipseRuns<Env>` | `gen<Env>IdeaRuns`, `gen<Env>EclipseRuns` |
| `generate[Env]ModMetadata` | `gen[Env]ModMetadata` |
| `migrateMappings<Env>` | `migrate<Env>Mappings` |
| `exportMappings<Env>` | `export<Env>Mappings` |
| `exportMainDevBundle`, `downgradeMainClasses`, `validateMainAccess`, … | `exportDevBundle`, `downgradeClasses`, `validateAccess` — `main` drops the infix |

## Declared-source provider (`legacydist`)

New in 2.0: environments whose game jar has no manifest-backed
provider declare the artifact directly instead of shipping a bespoke
provider — BTA/ReIndev-style jars, pre-1.8 bukkit, offline/air-gapped
artifacts:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        minecraft {
            main.legacydist {
                file("libs/bta-7.3-client.jar") // or maven("g:a:v"),
                // url("https://...", sha256 = "..."),
                // producer("./tool", "dist", output = "out.jar")
                namespace = "official"          // jar's prod namespace
                format = "obf"  // optional — raw->dev remap view
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        minecraft {
            main.legacydist {
                file("libs/bta-7.3-client.jar") // or maven("g:a:v"),
                // url("https://...", "..."),
                // producer("./tool", ["dist"] as String[], [], "out.jar")
                namespace = "official"          // jar's prod namespace
                format = "obf"  // optional — raw->dev remap view
            }
        }
    }
    ```

`legacydist {}` selects `provider("legacydist")` implicitly (apply
`xyz.wagyourtail.unimined.provider.legacydist` to register it); the
jar is cached by descriptor and reported with the declared
namespace/format. URLs are pinned by SHA-256 (warned-but-allowed when
omitted); maven coordinates resolve at declaration time; `producer()`
runs its declared executable inside the artifact transform and
requires the artifact's `output` path (Groovy passes the vararg
slot as an explicit `String[]` — see the example). A `format`
naming a registered provider (e.g. `"devbundle"`) routes there
instead; `devbundle { }` and the `bta`/`reindev`/`nsss`/`custom`
presets accept the same source verbs.

## Runs

| 1.x | 2.0 |
|---|---|
| `runs.off` global kill-switch | `unimined.runs { enabled.set(false) }` — disables every generated run task; `enabled` also exists per run config |
| `preLaunch(name) { }` / `preLaunch(name, task)` | `beforeRun { }` / `beforeRun(task)` on the run config, or `tasks.named("prepareRun<Name>") { }` |
| `runs.configFirst(name) { }` | **kept** — `runs { configFirst(name) { } }` applies ahead of `named`/`create`/`configureEach` config (declare it before other configuration of the run) |
| `RunConfig.setProperty(key, closure)` `${key}` substitution | **kept** — `setProperty(key, value)` (String / `Provider` / `() -> String` / Groovy closure, all lazy) and the `launchProperties` map; `${key}` substitutes across `jvmArgs`/`programArgs`/`systemProperties`/`environmentVariables` at launch, winning over env defaults and loader keys |
| `runObfClient`/`runObfServer` runs | `prodClient`/`prodServer` run configs (`runProdClient`/`runProdServer` tasks) — the `prod` flag launches the prod-namespace artifact |

New-in-2.0 run knobs you'll meet while porting — `type` (bind a run
to a loader's `data`/`junit`/`gameTestServer` launch shape),
`unitTest`/`unitTestSources` (JUnit `test<Name>` twins),
`loadedMods` (`mods { }` group subset), `systemProperties`,
`logLevel`/`loggingConfigFile`, `additionalRuntimeClasspath` +
the `run<Name>Dependencies` bucket, `stdin`, `features`,
`width`/`height`, `downloadAssets`, `ideName`/`ideFolderName`/
`ideConfigGenerated`/`singleInstance`, `from()` run inheritance —
all documented in [Run configs](guide/run-configs.md#runconfig-fields).

### Auth

`unimined.auth.enabled` is gone. 2.0 uses **per-run** `auth { }`
blocks, with `runs { auth { } }` as the shared default; with no
`auth {}` declared the run launches offline. A run declaring
`auth { microsoft() }` with an empty credential store fails fast
instead of prompting mid-launch — authenticate once via the
`loginMicrosoft` task or a stored token (see
[Auth](guide/auth.md)).

## Mappings

| 1.x | 2.0 |
|---|---|
| `stubs { }` / `hasStubs()` inline stub rows (`c`/`f`/`m`) | `mappings { stubs("official", "named") { } }` — same `c`/`f`/`m` row verbs against declared namespaces, composes through the normal pipeline and participates in the mapping cache key |
| `yarnv1(build)` tiny-v1 yarn | `mappings { yarnv1("build") }` — restored, still stitch-filled |
| `ExportMappingsTask` | `unimined.exportMappings("<envId>") { }` — registers `export<Env>Mappings`, writing the resolved env tree to a file in a chosen format |
| `spigotDev(mcVersion)` | `mappings { spigot() }` (or `builtin("spigot")`) — resolves the BuildData archive for the env's MC version |
| `mixinRemap { off(); disableRefmap(keys) }` | `remap { <surface> { modArtifacts { off() / disableRefmap(...) } } }` — auto-gating on jar markers still applies; these are the manual overrides |
| `customIntermediaries` plain var | `Property<Boolean>` on `mappings { }` — `customIntermediaries.set(true)` in Kotlin, `customIntermediaries = true` still works in Groovy |
| `AccessConvert.aw2at`/`at2aw`/`mergeAws` helpers | internal — declared `accessWidener`/`accessTransformer` files (or `access { }` rules) are converted to the loader's canonical format automatically during `processResources` |

`hierarchyNameFill` is new rather than moved — a `Property<Boolean>`
tri-state (unset auto-enables with Ornithe enricher sources) that
fills unmapped overrides from the real class hierarchy; see
[Mappings](guide/mappings.md#bytecode-hierarchy-fill).

## Source generation

| 1.x | 2.0 |
|---|---|
| `generator("g:a:v")` arbitrary decompiler dep | `unimined.source { generator("g:a:v") }` — a bare version string still resolves to `org.vineflower:vineflower:<v>`; sets `engine = "external"` + `dependency` |
| `jvmArgs`, `javaVersion`, `args` | same names on `unimined.source { }`; `engine`, `dependency`, `options` also exposed directly |

The `external` engine wraps any Fernflower-CLI-compatible artifact;
`vineflower` remains the default builtin.

## Removed extension points (SPI breaking changes)

1.x exposed implementation callbacks that 2.0 replaces with the
step-spec pipeline. There is no direct port — write a `*StepSpec` or
transformer claim instead:

- `customPatcher`, `mcPatcher`, `minecraftRemapper`,
  `remapper(TinyRemapper.Builder)`, `tinyRemapperConf`,
  `addResourceRemapper`, `addExtension`, `config`,
  `replaceJSRWithJetbrains` → remap step specs
  on each `remap { }` surface (`jsr305ToJetbrains` for the last).
- `onMergeFail`, `canCombine` overrides → fixed merge semantics —
  declare merges via `combineWith`/`from`; merge configuration
  knobs are `stripBundledServerLibraries`, `mergeExcludeGlobs`,
  `mergeIncludeGlobs` on the env.
- `defaultRemapSourcesJar`, `createJarTask`, `isMinecraftJar`,
  `minecraftDependency`, `forgeMCVersion`, `useUnionRelaunch`,
  `mods.remap(arbitraryConfiguration)`, `getClasspathAs`,
  `catchAWNamespaceAssertion`, `loader(dep)` arbitrary-notation
  overload, `obfuscated`, `combinedWithList`, `localCache`,
  `minecraftData`, `minecraftFileDev`, `minecraftSourceFileDev`,
  `mergedOfficialMinecraftFile` → no equivalent; open an issue if a
  workflow depended on one. (`defaultRemapJar` does survive as an
  env `Property<Boolean>` — `env.defaultRemapJar.set(false)` — the
  sources-jar twin does not.)
- `-Dfabric.remapClasspathFile` / `classPathGroups` /
  `intermediaryClasspath` → deliberate redesign: mod dependencies
  arrive pre-remapped through `mods` handling; no system-property
  seam. Classpath-embedded mods built against a *foreign* namespace
  are not detected or runtime-remapped — they are remapped as-if at
  `modSourceNamespaces` and fail at runtime; set the env's
  `modSourceNamespaces` or use `remap.remappedViews(...)`.
  The classpath-*grouping* half does have an equivalent — declare
  per-mod source-set groups in the env's `mods { }` container and
  select them per run with `loadedMods` (serializes onto
  `fabric.classPathGroups`/`MOD_CLASSES`; see
  [Environments](guide/environments.md#mod-classpath-groups)).
