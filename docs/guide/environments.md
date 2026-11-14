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
  mods contribute — set `false` to isolate the dev jar. The
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
  filter accepts it; `libraryCoordFilters` maps whole coords;
  `obfuscatedLibraries` flags coords that remap with the MC jar.
- `combinedSources` / `combinedOutputs` — file collections
  populated by `combineWith`; read-mostly, used by generated
  source jars and mod packaging.

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
  application, canonical merge, metadata reference.
- `combineWith` propagates access rules alongside the declared file
  collections, so combined environments share declarations.

### Auto-fill flags

Metadata auto-discovery toggles (see
[metadata](metadata.md#discovery)):

| Property | Default | Gates |
|---|---|---|
| `discoverMixinConfigs` | `true` | `*.mixins.json` resources auto-fill the `mixins` array |
| `discoverGametestEntrypoints` | `true` | compiled-class scan for loader gametest interfaces/annotations |
| `interfaceInjectionScan` | `false` | mixin-class scan deriving `inject-interface` rules (opt-in — `access { injectInterface(...) }` is the always-on path) |

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
