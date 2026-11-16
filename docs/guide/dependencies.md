# Dependencies

## Mod dependencies (artifacts)

Each env exposes `mod*` configurations scoped to its source set —
mod jars land remapped onto your mappings:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    dependencies {
        modImplementation("maven:coord")   // compile + runtime
        modRuntimeOnly("maven:coord")      // runtime only
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    dependencies {
        modImplementation("maven:coord")   // compile + runtime
        modRuntimeOnly("maven:coord")      // runtime only
    }
    ```

Named envs prefix the bucket (`serverModImplementation`,
`serverModRuntimeOnly`). A mod needed only at compile time
(annotation-processor stubs, javadoc-only references) goes on the
plain `compileOnly` — plain-library jars are never remapped, and
mod-remap only matters for jars that reach a runtime classpath. For
a remapped compile-only view, declare a
`remap.remappedViews(...)` view over your own bucket
([below](#configuration-reference)).

Mod jars are remapped from the environment's *mod source namespace*
to its dev namespace — they must already be built against the mod
source namespace (usually the loader's production namespace, e.g.
`intermediary` on Fabric). A jar built against any other namespace is
remapped as if it were at the declared one and breaks at runtime —
there is no per-jar namespace sniffing (the 1.x
`remapClasspathFile`/`classPathGroups` runtime-remap machinery is
gone by design). If your mod set mixes namespaces, set the env's
`modSourceNamespaces` to match or declare per-namespace
`remap.remappedViews(...)` and wire the buckets yourself.

Whether a jar remaps at all is decided by a marker scan of its
contents (mod metadata, namespace attributes, MC references); plain
libraries pass through. Metadata reads are qmj5-tolerant — a
`quilt.mod.json` written in JSON5 (comments, trailing commas, bare
keys) parses the same as strict JSON, both in dependency jars and in
your own resources. Loom's manifest contract is honored —
`Fabric-Loom-Remap: false` opts a jar out even when markers exist,
`true` forces remap of an unmarked jar. The user-side escape is
`forceRemap { dep -> … }` on the environment (NeoGradle's
`forceDeobfuscation` equivalent) — predicates select components that
must remap regardless of markers:

```kotlin
unimined.minecraft {
    sourceSet("main") {
        // …
        forceRemap { it.group == "com.example" && it.name == "obf-lib" }
    }
}
```

`forceRemap` also accepts a `group:name[:version]` coordinate string
(`forceRemap("com.example:obf-lib")`); malformed notation fails the
build with an actionable error.

## Repositories

Loader environments auto-register the mavens their artifacts resolve
from (Mojang Libraries, the loader's own maven, and friends) — no
`repositories { }` entries are needed for unimined-managed
coordinates. Your own repos still go through the normal
`repositories { }` block, and the `uniminedRepos` catalog can ensure
a known repo by name (`uniminedRepos.fabric.ensure()`).

After the build script evaluates, unimined runs repository
diagnostics automatically — the FG7 `checkRepos` equivalent, but as
post-DSL validation, not a task you invoke. The checks are warn-only
and land in the Gradle problems report (`unimined/repo-missing`,
`unimined/repo-order`) as well as the console:

- every repo unimined *ensured* earlier is still present — a repo
  removed or renamed mid-configuration is flagged;
- `MavenRepo` (Maven Central) must precede `Mojang Libraries` —
  module artifacts pin to the repo that served their metadata, so a
  mojang-first order hides central-only classifiers (e.g. netty
  natives).

A `dependencyResolutionManagement.repositoriesMode` build is
honored rather than bypassed: a repo already declared in settings
satisfies the ensure, `FAIL_ON_PROJECT_REPOS` without one fails with
the settings block to paste, and `PREFER_SETTINGS` reports a problem
and skips the project-level registration.

## Jar-in-jar (include)

Each env exposes an `include` configuration (`<env>Include` for named
source sets). Artifacts declared there are embedded inside the
production jar and declared in its loader metadata:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    dependencies {
        include("maven:coord")
        include(files("libs/local.jar"))
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    dependencies {
        include("maven:coord")
        include(files("libs/local.jar"))
    }
    ```

`include` is an ordinary declarable configuration, so bulk
composition rides `extendsFrom` rather than a dedicated option —
e.g. `configurations { include extendsFrom(embedThese) }` embeds
everything declared on a custom bucket. Inherited deps still produce
`version.range` metadata entries, so platform-include-style grouping
needs no extra DSL.

Two formats, chosen per environment — normally by the loader claim
(fabric-family → `fabric`, forge → `jarjar`) or explicitly:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        transform {
            sourceSet("main") {
                features { nestedJars("jarjar") } // or "fabric"
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        transform {
            sourceSet("main") {
                features { nestedJars("jarjar") } // or "fabric"
            }
        }
    }
    ```

The transform knobs can also sit directly on the loader declaration —
`loader(name, version) { }` receives the same environment's transform
spec (`nestedJars { }` plus `binding(key, value)` for feature
bindings), so packaging config colocates with the loader it belongs
to:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        minecraft {
            forge("47.2.0") {
                nestedJars { recompress.set(false) }
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        minecraft {
            forge("47.2.0") {
                nestedJars { recompress.set(false) }
            }
        }
    }
    ```

Both paths write the same spec — pick whichever reads better;
`loader(name) { }` works too for loaders that resolve their own
version. The env-scoped `transform { }` verb reaches the same spec
directly (`minecraft { transform { nestedJars { } } }` for `main`,
or inside `sourceSet("x") { }` for named environments).

- **`fabric`** — jars pack under `META-INF/jars/` and the loader
  metadata key (`jars` in `fabric.mod.json`, `quilt_loader.jars` in
  `quilt.mod.json`) gains `{file}` rows. Nested jars missing loader
  metadata get a synthesized stub so the loader accepts them.
- **`jarjar`** — jars pack under `META-INF/jarjar/` (STORED — forge's
  runtime byte-range-reads them) plus `META-INF/jarjar/metadata.json`
  rows in the `net.neoforged.jarjar` schema (`identifier`, `version`
  `range`/`artifactVersion`, `path`, `isObfuscated`). File
  dependencies without Maven coordinates fall back to
  `Automatic-Module-Name` / `Implementation-Version` manifest
  attributes.

### Entry type marking (jarjar)

FML classifies every discovered jar by the `FMLModType` manifest
attribute — `MOD`, `LIBRARY`, `GAMELIBRARY` (nested-jar default),
`LANGPROVIDER`, `CUSTOM` — which also picks its JPMS layer:
`LIBRARY`/`LANGPROVIDER` land in the PLUGIN layer (visible to
language providers), `MOD`/`GAMELIBRARY` in GAME. Stamping it is how
you mark a nested jar for FML's mod pipeline (scan, runtime remap)
without `mods.toml` inside it:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        transform {
            sourceSet("main") {
                nestedJars {
                    fmlModType.set("LIBRARY") // default for every entry
                    fmlModTypes.put(
                        "com.example:lang-lib", "LANGPROVIDER") // per g:a
                    fmlModTypes.put("local.jar", "MOD") // per file name
                    obfuscated.put("com.example:obf-lib", true)
                }
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        transform {
            sourceSet("main") {
                nestedJars {
                    fmlModType.set("LIBRARY") // default for every entry
                    fmlModTypes.put(
                        "com.example:lang-lib", "LANGPROVIDER") // per g:a
                    fmlModTypes.put("local.jar", "MOD") // per file name
                    obfuscated.put("com.example:obf-lib", true)
                }
            }
        }
    }
    ```

Unset entries aren't blindly left alone — during the rewrite pass
the embedder resolves layer signals in order:

| Signal | Stamps | Why |
|---|---|---|
| the jar's own `FMLModType` manifest attribute | keeps it | the author knows its layer — outranks every heuristic |
| `META-INF/services/…​IModLanguageProvider` (or `IModLanguageLoader`) | `LANGPROVIDER` | providers must be PLUGIN-layer — the Kotlin-for-Forge case |
| `META-INF/mods.toml` / `neoforge.mods.toml` | `MOD` | it is a mod file |
| `Obfuscated-By` manifest attribute | `GAMELIBRARY` | needs game-layer runtime remap |
| parent jar's `FMLModType` is `LIBRARY`/`LANGPROVIDER`/`CUSTOM` | `LIBRARY` | plugin-layer parents propagate to their deps — FML does the same at runtime post-1.20 |

Explicit config always wins over everything, including a shipped
`FMLModType`; `LIBRARY` is never auto-detected (nothing inside a jar
proves it doesn't touch game classes) beyond the parent-propagation
rule. With no signal at all the jar embeds unstamped — FML's
`GAMELIBRARY` default applies, which can't break anything since GAME
sees PLUGIN.

Detection only rides the rewrite pass: `recompress.set(false)`
embeds verbatim with no stamp unless you declare a type explicitly —
which still forces the rewrite (the manifest is emitted first so
`JarFile` sees it). `isObfuscated` otherwise detects from the
nested jar's own `Obfuscated-By` manifest attribute.

`include` is **not** a classpath dependency — declare
`modImplementation` (fabric-style mods) or `implementation`
(jarjar library deps) alongside when dev runs need the contents.

### Recompression

By default embedded jars are rewritten before packing: entries sorted
and stamped at a fixed epoch for reproducible builds, STORED for
`fabric` so the outer jar compresses the whole nested stream once
(verbatim embedding would double-compress for ~zero gain). Opt out per
environment:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        transform {
            sourceSet("main") {
                nestedJars { recompress.set(false) } // embed verbatim
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        transform {
            sourceSet("main") {
                nestedJars { recompress.set(false) } // embed verbatim
            }
        }
    }
    ```

### Identity environments

Environments with no dev→prod namespace remap (identity envs —
e.g. unobfuscated targets, or `devNamespace == modSourceNamespaces`)
skip `remapJar` entirely. The embed runs as a post-pass on the plain
`jar` task there instead, so `include` behaves identically whether or
not a remap hop exists.

Nested jars stamped with a different `Unimined-Mapping-Namespace`
than the production target produce a warning — embed the
production-namespace artifact.

## Loader apis

`unimined.loaderApi(name, version)` resolves a loader's api artifact
bundle; `loaderApiModule(name, module, version)` resolves one
sub-module. Both also record the api modid into `mod { depends }`.

--8<-- "generated/tables/api-providers.md"

## Metadata-only dependencies

`mod { depend("modid", "versionRange") }` declares runtime deps in the
emitted mod metadata without touching the classpath.

## Tool coordinates

Unimined resolves several build-time tools into detached
configurations — JST (source-transform enrichment), `unprotect`
(forge dev launches), `mcpcleanup`/`DiffPatch` (mcpconfig pipelines),
the jvmdowngrader java-api, the bukkit classpath-plugin-loader, and
`stitch` (yarn-v1 field-name proposals). Each coordinate is a
`Property<String>` on the `uniminedTools` extension, so the version —
or a fork — is user-overridable:

=== "Kotlin `build.gradle.kts`"

    ```kotlin
    uniminedTools {
        jst.set("net.neoforged.jst:jst-cli:2.1.0")
        diffpatch.set("com.example:DiffPatchFork:1.0")
    }
    ```

=== "Groovy `build.gradle`"

    ```groovy
    uniminedTools {
        jst.set("net.neoforged.jst:jst-cli:2.1.0")
        diffpatch.set("com.example:DiffPatchFork:1.0")
    }
    ```

Available keys and defaults:

| Key | Default coordinate | Used for |
|---|---|---|
| `jst` | `net.neoforged.jst:jst-cli:2.0.11` | Forge userdev source-pipeline enrichment |
| `unprotect` | `io.github.juuxel:unprotect:1.3.0` | Forge-era dev launches |
| `mcpcleanup` | `net.minecraftforge:mcpcleanup:2.3.6` | Notch-obf userdev sources chain |
| `diffpatch` | `net.minecraftforge:DiffPatch:2.0.12` | MCPConfig diff/patch steps |
| `jvmdowngraderJavaApi` | `xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:2.0.1` | `jvmdg` adapter runtime dep |
| `classpathPluginLoader` | `xyz.wagyourtail.unimined:classpath-plugin-loader:0.1.0-SNAPSHOT:all` | Bukkit plugin-classpath agent |
| `stitch` | `net.fabricmc:stitch:0.6.2` | yarn-v1 `proposeFieldNames` |

Overrides match by the default `group:artifact` — wherever the
default tool coordinate appears (including inside mcpconfig
`functions` tables and userdev tool lists) the overridden notation is
substituted. Coordinates a loader profile itself declares stay
profile-driven unless their `group:artifact` matches a key above.

## Configuration reference

Every bucket below is scoped per environment. `<env>` prefixes are
the environment id (`serverModImplementation`), except the `main`
env which elides its own prefix on `mod*`/`include`/`minecraft*`
(`modImplementation`, `include`, `minecraftLibraries`) but keeps it
on `unimined*` names (`uniminedMainAccessTransformer`).

**Declarable — you put dependencies in:**

| Configuration | Lands on | Remapped? | Purpose |
|---|---|---|---|
| `modImplementation` / `<env>ModImplementation` | compile + runtime | yes → dev ns | mod jars to compile and run against |
| `modRuntimeOnly` / `<env>ModRuntimeOnly` | runtime | yes → dev ns | mods needed only at launch |
| `include` / `<env>Include` | production jar (jarjar) | repacked | embed jars inside the mod artifact — *not* a classpath dep ([jar-in-jar](#jar-in-jar-include)) |
| `unimined<Env>AccessTransformer` | access docs | — | coordinate-consumed access docs (NG `accessTransformer` parity) |
| `run<Name>Dependencies` | one run's classpath | — | per-run classpath additions for `additionalRuntimeClasspath` maven coords |
| `minecraft` / `<env>Minecraft` | MC artifact stub | — | the generated `unimined.generated` MC stub dep — declared for you |
| `mergedLoader<Env>` (and `forgeMergedLoader<Env>`, `fabricMergedLoaderLibraries<Env>`) | compile only | — | secondary loaders on merged envs — wired by repeated `loader(...)` |

Standard `java` buckets (`implementation`, `api`, `compileOnly`,
`compileOnlyApi`, `runtimeOnly`, `annotationProcessor`) behave
normally — non-mod jars pass through un-remapped, and the mod-bucket
exclusions propagate through `extendsFrom` so mod transitives can't
duplicate the env's loader. To remap an *arbitrary* bucket to the
dev namespace, view it through `remap.remappedViews(...)`
([Remap pipeline](remap-pipeline.md)).

**Resolvable — unimined resolves these for you (read-only views):**

| Configuration | Produces |
|---|---|
| `minecraftRaw` / `<env>MinecraftRaw` | the source-namespace MC artifact |
| `minecraftDev` / `<env>MinecraftDev` | the dev-namespace MC artifact (compile classpath) |
| `minecraftProd` / `<env>MinecraftProd` | the production-namespace artifact (prod launches, remap analysis) |
| `minecraftLibraries` / `<env>MinecraftLibraries` | the env's resolved MC library set |
| `minecraftDeclaredLibraries` / `<env>MinecraftDeclaredLibraries` | the version-json–declared library subset |

**Consumable — other projects consume these from your project:**

| Configuration | Carries |
|---|---|
| `unimined<Env>` component | the env's remappable product + variants (published via `unimined-publish`) |
| `unimined<Env>AccessTransformerElements` | the env's own access docs, `accesstransformer` category |
| `*SourcesElements` | sources jars mirroring the java variants (dev-variant publishing) |
