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
            fabric("0.16.0")
            // or a named source-set env:
            sourceSet("server") {
                forge("47.2.0")
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
            fabric("0.16.0")
            // or a named source-set env:
            sourceSet("server") {
                forge("47.2.0")
                side.set(Side.SERVER)
            }
        }
    }
    ```

- `sourceSet(name)` binds the env to a source set (default `main`);
  sibling envs are independent — `combineWith("other")` (optionally
  with `EnvironmentSection`s) composes one into another, and nested
  envs (`datagen()`/`gametest()`) inherit their parent implicitly.
  Copies/combinations propagate `DEPENDENCIES` (the env's dependency
  buckets) by default — see
  [section propagation](../guide/multi-project.md#section-propagation-dependencies-included).
- `side` selects `Side.CLIENT`/`SERVER`/`MERGED`
  ([example](../generated/side-server.gradle.kts) / [groovy](../generated/side-server.gradle));
  `Side.COMMON` is applied by `splitEnvironmentSourceSets()`
  ([below](#split-environment-source-sets)).
- Multiple envs in one project = flat multi-env;
  [multi-module](../guide/multi-project.md) is the alternative.

Envs are also the scope of `mod { }` metadata and `runs { }`.

**Strict checks.** `minecraft { checks { } }` carries the enforcement
levels for Unimined's environment validations — the scope 1.x's
`unimined.footgunChecks` Boolean gated, now split into named checks
(1.x's jar lint didn't exist; 2.0's `remap { checks { jarLint } }` is
unrelated):

- `combination` — `combineWith` consistency: a combined env must share
  its sources' Minecraft version, loader family, and dev namespace.
  Combine-*cycle* rejection is not gated — a cyclic chain can't
  resolve at all, so it always fails.
- `versionConflict` — an env inheriting sections from another must
  resolve the same MC version (or leave it unset to inherit).

Both take `CheckLevel.ERROR` (default, fails the build), `WARN`
(logged finding), or `OFF` (skip):

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        minecraft {
            checks {
                combination.set(CheckLevel.OFF)
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        minecraft {
            checks {
                combination.set(CheckLevel.OFF)
            }
        }
    }
    ```

**Task naming.** Per-environment tasks follow `verb<Env>Noun` — the
environment id (capitalized) sits between the verb and the object:
`genFabricSources`, `validateForgeAccess`, `remapClientJar`,
`exportPaperDevBundle`. The `main` environment elides the infix:
`genSources`, `validateAccess`, `remapJar`. Task names normalized
onto this convention keep their old shape as a deprecated alias —
`gradlew oldName` warns and runs the canonical task.

## Environment knobs

Everything below lives inside an env block (`minecraft { … }` or
`sourceSet("x") { … }`):

- **Dependencies** — `dependencies { }` declares ordinary
  (`implementation`/`compileOnly`/`runtimeOnly`/`api`) and mod
  (`modImplementation`/`modCompileOnly`/`modRuntimeOnly`/`include`)
  buckets scoped to the environment — the KMP-style surface over the
  string configuration names; see
  [Dependencies](dependencies.md#environment-scoped-dependencies).
- **Java** — `javaVersion` sets the compile/run floor (conventioned
  from the loader descriptor); `useToolchains` toggles Gradle
  toolchains for env-scoped compiles (conventioned from the
  extension-level `unimined.useToolchains`); `requiredJavaVersion`
  reads the effective requirement — version-json `javaVersion`
  raised by your `javaVersion` — and feeds launchers and tool-exec
  fallbacks. `compileJavaVersion` is the level `compile*` tasks
  actually emit at (toolchain selection, `--release`,
  `sourceCompatibility`/`targetCompatibility`, Kotlin `jvmTarget`)
  — it conventions to `requiredJavaVersion`, and the
  [jvmdg adapter](../adapters/jvmdg.md)'s `source` raises it without
  moving the run floor. Compile/runtime classpath dependency resolution
  advertises the env runtime floor even when `compileJavaVersion` is
  lower, so Java-17+ Minecraft/loader libraries remain selectable while
  jvmdg emits older bytecode.
- **Access widening** — `accessWideners` / `accessTransformers` are
  file collections of `*.accesswidener` / `accesstransformer.cfg`
  applied to the dev artifact; `transitiveAccessWideners` (default
  `true`) lets AW `transitive-*` directives from compile-classpath
  mods contribute — set `false` to isolate the dev jar.
  `dependencyInterfaceInjection` (default `true`, loom's
  `interfaceInjection.enableDependencyInterfaceInjection`) controls
  dependency-declared `*injected_interfaces` independently — mods
  injecting interfaces you compile against still apply when
  `transitiveAccessWideners` is off. The
  [`access { }` block](#access-declarations) below covers inline
  rules, file declarations, and resource auto-discovery.
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
  filter accepts it; `libraryCoordFilters` maps whole coords. These
  channels apply both to Mojang/provider version libraries and to
  library coordinates flattened out of loader metadata (forge userdev
  and FG2 `dev.json`/`version.json`); structured loader entries keep
  their platform rules, and natives-only entries do not become bogus
  plain-jar dependencies. `obfuscatedLibraries` flags coords that remap
  with the MC jar.
- **LWJGL 3 swap** — `lwjgl3 { }` drops every `org.lwjgl.lwjgl:*`
  library (jars *and* natives entries) and puts lwjgl3 artifacts on
  the classpath and the host natives set instead — the
  RetroFuturaGradle surface for running old Minecraft on modern
  JVMs:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    minecraft {
        sourceSet("main") {
            version = "1.7.10"
            lwjgl3 {
                version.set("3.3.3")
                bindings.add("stb")           // + natives
                nativeslessBindings.add("harfbuzz")
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    minecraft {
        sourceSet("main") {
            version.set("1.7.10")
            lwjgl3 {
                version = "3.3.3"
                bindings.add("stb")
                nativeslessBindings.add("harfbuzz")
            }
        }
    }
    ```

  Natives resolve under the host's lwjgl3 classifier
  (`natives-windows`/`natives-linux`/`natives-macos` + `-x86`/`-arm64`
  variants). The lwjgl3ify mod itself — for environments needing the
  lwjgl2 API at runtime — is a normal `modImplementation`/`include`
  dependency; this block only swaps the bindings.
- **Ornithe artifact passes** — `ornithe(…)` environments
  get Ploceus's default-on Minecraft-jar passes automatically:
  `condor` synthesizes `LocalVariableTable` debug info on the dev
  artifact (versions before 1.8.2 — every alpha/beta build plus
  releases and snapshots up to `1.8.2-pre*`; infdev and older stay
  out) so decompilers and Mixin `@Local` captures see real variable
  slots, and `preen` clears `ACC_FINAL`/`ACC_SYNTHETIC`/`ACC_BRIDGE`
  on existing ProGuard-merged bridge/implementation hybrids on every
  Ornithe version (without synthesizing methods). The newest
  `net.ornithemc:nests` build published for the env's version + side
  also joins the mapping chain automatically (see
  [Mappings](mappings.md#ornithe-enrichers)). Each pass opts out
  individually:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    minecraft {
        sourceSet("main") {
            version = "b1.7.3"
            ornithe("0.19.5")
            disableLvtPatch()   // skip the condor LVT pass
            disablePreen()      // skip the merged-bridge access pass
            disableNests()      // skip the auto nests source
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    minecraft {
        sourceSet("main") {
            version.set("b1.7.3")
            ornithe("0.19.5")
            disableLvtPatch()
            disablePreen()
            disableNests()
        }
    }
    ```

  The passes scope to the environment artifact — remapped dependency
  views and `remapJar` outputs never see them. `patchLvts`,
  `applyPreen` and `autoNests` are the underlying booleans behind
  the `disable*` verbs.
- `combinedSources` / `combinedOutputs` — file collections
  populated by `combineWith`; read-mostly, used by generated
  source jars and mod packaging. `packagedCombinedOutputs` is the
  shadow-mode-filtered subset that ships inside the env's artifact —
  see [packaging modes](multi-project.md#packaging-modes-what-ships-in-the-artifact).

## Split environment source sets

Loom `splitEnvironmentSourceSets()` parity — compile-time
client/server separation on a single project:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        minecraft {
            version = "1.20.1"
            fabric("0.16.0")
            splitEnvironmentSourceSets()
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        minecraft {
            version.set("1.20.1")
            fabric("0.16.0")
            splitEnvironmentSourceSets()
        }
    }
    ```

`src/main` compiles against the **common** artifact — the intersection
of the client and server jars (`Side.COMMON`) — so shared code can't
reference client-only classes (`Minecraft`, screens, rendering).
Client-only code lives in `src/client`, which compiles against the
full client jar and sees `main`'s output (its compile classpath picks
up `main`'s classesDirs, its runtime classpath the full output).

- `main` → `Side.COMMON`; the `client` env (`src/client`) →
  `Side.CLIENT` and aux status — it inherits `main`'s
  loader/dependency/access sections and produces no separate published
  artifact or mod metadata of its own.
- `jar` folds the client output in, so `remapJar`/`remapSourcesJar`
  carry it through production; `sourcesJar` gains the client sources;
  `test` sees the client's classpath view.
- `runClient`/`runProdClient` — and any client-side run bound to
  `main` — launch through the client environment (the common jar
  can't launch a client); `runServer`/`runProdServer` stay on `main`,
  where the common jar is the server's view.
- On providers without genuinely split distributions (declared
  legacydist jars, dev bundles, BuildTools outputs) the common
  artifact aliases the merged jar — the client-only fence then only
  covers entries the shared jar lacks. Non-mergeable conversion
  products (independent forks like BTA/ReIndev/NSSS) reject `COMMON`
  outright — split-source-set mode needs real client+server
  distributions.
- The call is idempotent; a pre-existing `sourceSet("client")` env is
  repurposed as the split client.

## Access declarations

Everything in this section is **optional** — dropping a
`*.accesswidener`, `*.classtweaker`/`.ct`, or
`accesstransformer.cfg`/`*_at.cfg` file under the environment's
resource roots is all a file-based setup needs: it's detected,
applied to the dev artifact, shipped in the jar, and declared in
loader metadata with zero DSL. The `access { }` block below is for
declaring rules *inline* (no file at all) or attaching packaging
options to specific files.

`access { }` declares every access rule — inline directives,
existing files via `from(...)`, and interface injections — stored
semantically and emitted in whichever document format the
environment's loader consumes (`accessWidener`/`classTweaker` on
fabric-family, FML `accesstransformer.cfg` on forge-family). Rules
the target format can't express are converted when possible or
dropped with a build warning (`inject-interface`/`extend-enum` have
no AT analog; narrowing `at()` access has no AW form).

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        minecraft {
            sourceSets.main {
                access {
                    // rename / reformat the merged canonical doc
                    // (where the loader declares it in metadata) —
                    // here a legacy-FML-style AT at a custom `_at.cfg`
                    // path; format: "aw", "at", "legacy_at", default
                    // is the loader's consumed format
                    fileName = "META-INF/{modid}_at.cfg"
                    format = "legacy_at"

                    // fail the build when a declared target doesn't
                    // exist in the dev artifact — NFRT
                    // `--validated-access-transformer` parity; covers
                    // every own declaration channel (these rules,
                    // from(...) files, accessWideners/
                    // accessTransformers). Dependency docs are never
                    // validated. Default false.
                    validate = true

                    // existing files — format detected per file,
                    // routed like accessWideners.from(...) /
                    // accessTransformers.from(...)
                    from("src/main/resources/testmod.accesswidener")

                    // per-file packaging options where the loader
                    // declares the path — rides the accessFile(...){}
                    // channel: `output` ships the file at a custom jar
                    // path, `declare=false` applies it dev-side only,
                    // `format` forces interpretation when detection
                    // can't (e.g. a legacy-AT file with an odd name):
                    // "aw"/"accesswidener", "at"/"accesstransformer",
                    // "legacy_at"/"legacyat" — classTweaker is
                    // detected by its header, not settable
                    from("src/main/resources/extra.cfg") {
                        output = "META-INF/extra.cfg"
                        format = "legacy_at"
                    }

                    // AW directives (+ transitive* twins)
                    accessible("net/minecraft/class_123")
                    accessible("net/minecraft/class_123", "method_1", "()V")
                    extendable("net/minecraft/class_456")
                    mutable("net/minecraft/class_456", "field_7", "I")

                    // CT directives — interface injection / enum
                    // extension (dev jar + shipped CT where the loader
                    // parses it)
                    injectInterface("net/minecraft/class_123",
                        "com/example/IThing")
                    extendEnum("net/minecraft/class_789", "MY_CONST")

                    // raw FML AT entries — `at("public-f", "a/B", "f", "I")`
                    at("public", "com/example/Opened")
                }
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        minecraft {
            sourceSets.main {
                access {
                    fileName = "META-INF/{modid}_at.cfg"
                    format = "legacy_at"
                    from("src/main/resources/testmod.accesswidener")
                    from("src/main/resources/extra.cfg") {
                        output = "META-INF/extra.cfg"
                    }
                    accessible("net/minecraft/class_123")
                    injectInterface("net/minecraft/class_123",
                        "com/example/IThing")
                    at("public", "com/example/Opened")
                }
            }
        }
    }
    ```

- Every verb has a `transitive*` twin (`transitiveAccessible`, …)
  matching loom's dependents-visible semantics.
- `interfaceInjection(target, signature)` on the env is shorthand for
  `access { transitiveInjectInterface(...) }`.
- **Dev artifact** — generated declarations and discovered files apply
  to the dev Minecraft artifact before your sources compile, so
  widened members and injected interfaces resolve in `compileJava`.
- **Shipping** — the same declarations ride the declared-file channel:
  they convert into the loader's consumed format at the canonical jar
  path (`{modid}.accesswidener`, `META-INF/accesstransformer.cfg`) and
  are declared in loader metadata (`accessWidener` field / `FMLAT`
  manifest attribute). On fabric loader ≥ `0.18.0` the canonical doc
  upgrades to `classTweaker` when CT rules are present; older loaders
  get an AW-only doc and interface injections ride the
  `loom:injected_interfaces` custom key instead. A loaderless
  (`common`) env skips metadata entirely — the canonical doc ships
  *inside the jar* instead as a self-describing `classTweaker`
  (`format` also accepts `"ct"` there), so dependents discover it on
  their dependency scan and inherit its `transitive-*` rules.
- **Naming** — rules from *all* `access { }` calls merge into one
  canonical doc; `fileName = "..."` inside the block (or the
  `accessFileName` property) renames where that merged doc ships,
  where the loader's metadata declares the path (`{modid}` still
  expands). `format = "…"` inside the block (`"aw"`/`"at"`/
  `"legacy_at"`) overrides the emitted format — the default is the
  loader's consumed format, and overriding warns since the loader
  parses the declared path in its own expected format. Setting two
  *different* names is a configuration error, not two files — for
  multiple shipped access documents use `from(path) { output = "..." }`,
  which emits each named file independently. On loaders with no
  access-file convention the canonical name is ignored (warn-only).
- **Auto-discovery** — resource-side access files (patterns above)
  join the same channels without any declaration: dev-artifact
  application, canonical merge, metadata reference. Resource roots
  under the build directory are task outputs and are not scanned —
  declare a generated access file through the task's output file
  (`accessWideners.from(task.map { it.outputFile })`), which keeps
  the task dependency.
- `combineWith` propagates access rules alongside the declared file
  collections, so combined environments share declarations.

#### `validate<Env>Access`

Environments that declare access documents also get a
`validate<Env>Access` task (loom `validateAccessWidener` parity):
every own declaration — `access { }` rules, `from(...)` files,
`accessFile(...)`, `accessWideners`/`accessTransformers` — is checked
against the dev Minecraft artifact and the build fails listing each
declared target that matched nothing, without running a remap.
Dependency docs and coordinate-consumed docs are never validated.
`check` depends on it, so it runs in a normal `build`; the task only
exists when the environment actually declares access docs.

This is the standalone form of `access { validate = true }` — the
in-block flag validates during the remap pipeline instead; either
catches stale targets, the task form without needing a remap run.

### Auto-fill flags

Metadata auto-discovery toggles (see
[metadata](metadata.md#discovery)):

| Property | Default | Gates |
|---|---|---|
| `discoverMixinConfigs` | `true` | `*.mixins.json` resources auto-fill the `mixins` array |
| `discoverGametestEntrypoints` | `true` | compiled-class scan for loader gametest interfaces/annotations |
| `emitLoaderMetadata` | `true` | generated loader descriptors (`fabric.mod.json`, `mods.toml`, `mcmod.info`, …); `false` keeps hand-written descriptors authoritative while manifest attributes still emit |
| `interfaceInjectionScan` | `false` | mixin-class scan deriving `inject-interface` rules (opt-in — `access { injectInterface(...) }` is the always-on path); requires a declared `mod.id` — skipped with a warning otherwise |

## Mod classpath groups

Multi-mod projects tell the dev launch which classpath entries belong
to which mod via the env-scoped `mods { }` container — the group name
should match the mod id:

```kotlin
minecraft {
    sourceSet("fabric") {
        fabric("...")
        mods {
            register("mymod") {
                sourceSet("main")
                modFiles("libs/extra-mod-lib.jar")
            }
            register("integration") {
                sourceSet("integration")
            }
        }
    }
}
```

Each group joins the launch classpath and serializes into the loader's
mod-grouping channel:

- **fabric-family** — the `fabric.classPathGroups` system property
  (fabric-loader's classpath-group contract — loom `mods { }` parity),
  groups separated by a doubled path separator;
- **forge-family** — `id%%dir` entries appended to `MOD_CLASSES`
  alongside the loader's own source-set groups. Source-set outputs that
  ride the environment runtime classpath as file dependencies (for
  example `implementation(otherSourceSet.output)`) are autodetected
  into these groups so the dev runtime does not split them into
  conflicting automatic modules; explicit `combineWith` remains the
  clearer way to compose full environments.

A run loads every declared group by default; `loadedMods` on the run
selects a subset (see
[run configs](run-configs.md#runconfig-fields)). Groups inherit
through `inheritParent`/`combineWith` like other env state — a child
re-declaring a group name shadows the parent's.

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
