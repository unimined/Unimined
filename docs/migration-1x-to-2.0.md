# Migrating from 1.x to 2.0

Unimined 2.0 splits the monolithic `unimined { }` extension into
per-domain plugins with their own extensions. Groovy scripts that
call removed 1.x verbs now get a migration note naming the new
form; Kotlin DSL scripts fail at compile time — this table maps
old calls to their 2.0 equivalents.

## Environment configuration

| 1.x | 2.0 |
|---|---|
| `unimined.minecraft(sourceSet) { }` | `unimined.minecraft { }` + per-source-set env (`minecraft { sourceSet("main") { } }`) |
| `unimined.reIndev(sourceSet) { }` | env-level infra config inside `unimined.minecraft { }` |
| `unimined.bta(sourceSet) { }` | same — env-level inside `unimined.minecraft { }` |

Loader declaration moves onto the environment:
`loader("fabric", "0.16.0")` / `loader("fg1", "0.1.5")` /
`env.jarmod("x")` / `env.fg1("x")` / `env.customSearge()`.

Kept verbatim on the environment: `customGameProvider` (suppresses
the loader's built-in MC game-provider lookup for total-conversion
setups — emits the descriptor's `skipMcProvider` system property;
typically paired with an `empty-*` version, which still parses as a
resolve-nothing pseudo-version).

## Remap pipeline

| 1.x | 2.0 |
|---|---|
| remap hooks inside `unimined { }` | `unimined.remap { }` — step verbs (`unpick(...)`, `applyClassTweaker {}`, `sourceRemap {}`) per surface, both Groovy and Kotlin. Source remapping runs through UML's source remapper by default; `jst {}` remains as an explicit engine opt-in (verification/alternate engine), not a peer default |
| `unimined.migrateMappings(sourceSet) { }` | `unimined.migrateMappings("main") { }` (env id), or `unimined.migrate { }` |

## Repositories

The ~20 `unimined.*Maven()`/`unimined.*Repos()` helpers
(`fabricMaven`, `minecraftForgeMaven`, `curseMaven`, `jitpack`,
`spigot`, `quiltMaven`, `ornitheMaven`, `legacyFabricMaven`,
`neoForgedMaven`, `cleanroomRepos`, `modrinthMaven`, …) are gone:
loader repositories are **auto-registered per environment** in
2.0. Remove the calls; use a normal `repositories {}` block for
anything custom. `modsRemapRepo` is likewise managed internally.

## Other moves

| 1.x | 2.0 |
|---|---|
| mod/transform config inside `unimined { }` | `unimined.mod { }`, `unimined.transform { }` |
| global toolchain toggle | `unimined.useToolchains = false` |
| env java override | `unimined.javaVersion` (delegates to the `main` env) |
| `jvmArgs += "-javaagent:/path/agent.jar"` | `javaagents.add(file("agent.jar"))` on the run config — a typed file collection resolved to `-javaagent:` flags at launch |

Unknown or removed verbs raise a `GradleException` naming the
replacement — read the message; it points here.

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
                // producer("tool", "...")
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
                // url("https://...", "..."), producer("tool", "...")
                namespace = "official"          // jar's prod namespace
                format = "obf"  // optional — raw->dev remap view
            }
        }
    }
    ```

`legacydist {}` selects `provider("legacydist")` implicitly; the jar
is cached by content hash and reported with the declared
namespace/format. URLs are pinned by SHA-256 (warned-but-allowed when
omitted); maven coordinates resolve at declaration time.

## Runs

| 1.x | 2.0 |
|---|---|
| `runs.off` global kill-switch | `unimined.runs { enabled.set(false) }` — disables every generated run task; `enabled` also exists per run config |
| `preLaunch(name) { }` / `preLaunch(name, task)` | `beforeRun { }` / `beforeRun(task)` on the run config, or `tasks.named("prepareRun<Name>") { }` |
| `runs.configFirst(name) { }` | `runs { named(name) { } }` — configures the existing run or creates it if absent |
| `RunConfig.setProperty(key, closure)` `${key}` substitution | removed — set args/jvmArgs/env directly |

### Auth

`unimined.auth.enabled` is gone. 2.0 uses **per-run** `auth { }`
blocks; with no `auth {}` declared the run launches offline. A run
declaring `auth { microsoft() }` with an empty credential store fails
fast instead of prompting mid-launch — authenticate once via the
auth task or a stored token.

## Mappings

| 1.x | 2.0 |
|---|---|
| `stubs { }` / `hasStubs()` inline stub rows (`c`/`f`/`m`) | `mappings { stubs { } }` — same `c`/`f`/`m` row verbs, composes through the normal pipeline and participates in the mapping cache key |
| `yarnv1(build)` tiny-v1 yarn | `mappings { yarnv1("build") }` — restored, still stitch-filled |
| `ExportMappingsTask` | `unimined.exportMappings("<envId>") { }` — writes the resolved env tree to a file in a chosen format |
| `spigotDev(mcVersion)` | `mappings { spigot("mcVersion") }` (or `builtin("spigot")`) |
| `mixinRemap { off(); disableRefmap(keys) }` | `remap { <surface> { modArtifacts { off() / disableRefmap(...) } } }` — auto-gating on jar markers still applies; these are the manual overrides |
| `AccessConvert.aw2at`/`at2aw`/`mergeAws` helpers | internal — declared `accessWidener`/`accessTransformer` files are converted to the loader's canonical format automatically during `processResources` |

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
  `addResourceRemapper`, `replaceJSRWithJetbrains` → remap step specs
  on each `remap { }` surface.
- `onMergeFail`, `canCombine` overrides → fixed merge semantics.
- `defaultRemapSourcesJar`, `createJarTask`, `isMinecraftJar`,
  `minecraftDependency`, `forgeMCVersion`, `useUnionRelaunch`,
  `mods.remap(arbitraryConfiguration)`, `getClasspathAs`,
  `catchAWNamespaceAssertion`, `loader(dep)` arbitrary-notation
  overload → no equivalent; open an issue if a workflow depended on
  one.
- `-Dfabric.remapClasspathFile` / `classPathGroups` /
  `intermediaryClasspath` → deliberate redesign: mod dependencies
  arrive pre-remapped through `mods` handling; no system-property
  seam. Classpath-embedded mods built against a *foreign* namespace
  are not detected or runtime-remapped — they are remapped as-if at
  `modSourceNamespaces` and fail at runtime; set the env's
  `modSourceNamespaces` or use `remap.remappedViews(...)`.
