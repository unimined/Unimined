# Dependencies

## Mod dependencies (artifacts)

Each env exposes `mod*` configurations scoped to its source set —
mod jars land remapped onto your mappings:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    dependencies {
        modImplementation("maven:coord")
        modCompileOnly("maven:coord")
        modLocalRuntime("maven:coord")
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    dependencies {
        modImplementation("maven:coord")
        modCompileOnly("maven:coord")
        modLocalRuntime("maven:coord")
    }
    ```

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
            loader("forge", "47.2.0") {
                nestedJars { recompress.set(false) }
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        minecraft {
            loader("forge", "47.2.0") {
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

Unset entries embed untouched — FML's `GAMELIBRARY` default applies.
A stamp forces the inner rewrite (the manifest is emitted first so
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
