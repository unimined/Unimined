# Products

The *product* is which Minecraft distribution an env runs — orthogonal
to the loader. `minecraft { }` is the native product; others get their
own blocks:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        bta { loader("bta-babric", "1.7.6") }       // Better Than Adventure
        reindev { foxloader("…") }                // ReIndev + FoxLoader
        nsss { file("dist.jar") }                   // Not So Seecret Saturday
        custom("myproduct") { file("custom.jar") }  // arbitrary dist
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        bta { loader("bta-babric", "1.7.6") }       // Better Than Adventure
        reindev { foxloader("…") }                // ReIndev + FoxLoader
        nsss { file("dist.jar") }                   // Not So Seecret Saturday
        custom("myproduct") { file("custom.jar") }  // arbitrary dist
    }
    ```

Products nest their loader inside the product env — the loader axis
stays the same either way.

Full generated build scripts: [BTA](../generated/product-bta.gradle.kts)
/ [custom product](../generated/product-custom.gradle.kts) (`.gradle`
siblings are the Groovy twins).

## Declared-source dists (`legacydist`)

`xyz.wagyourtail.unimined.provider.legacydist` is the provider behind
envs that declare their game jar directly — `legacydist { }` selects
it implicitly, and `custom(...) { file(...) }` envs negotiate to it
from the declared source. Apply the plugin, then declare the artifact
inside the env:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    plugins {
        id("xyz.wagyourtail.unimined.provider.legacydist")
    }

    unimined {
        minecraft {
            sourceSet("main") {
                legacydist {
                    file("libs/dist.jar")                    // local jar
                    // url("https://…", sha256 = "…")         // pinned fetch
                    // producer("./gradlew", "buildDist")     // external builder
                    namespace = "official"                   // prod namespace
                    format = "obf"                           // optional hint
                }
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    plugins {
        id 'xyz.wagyourtail.unimined.provider.legacydist'
    }

    unimined {
        minecraft {
            sourceSet("main") {
                legacydist {
                    file 'libs/dist.jar'
                    namespace = 'official'
                }
            }
        }
    }
    ```

The declared jar is consumed as-is — no manifest is consulted, so it
works for non-version artifacts (BTA/ReIndev overlays, pre-1.8 bukkit,
air-gapped jars). `file()` copies into a descriptor-keyed cache under
the shared unimined cache; `url()` downloads once with the SHA-256 pin
verified before publish (unpinned URLs warn); `maven()` resolves from
the project's repositories at declaration time; `producer()` runs the
declared executable inside the artifact transform and expects its
`output` in the work dir. `format = "obf"` marks the jar as obfuscated
so it rides the raw→dev remap view; a `format` matching another
registered provider (`"devbundle"`) routes there instead.

## Paperweight dev bundles

`xyz.wagyourtail.unimined.provider.devbundle` consumes
paperweight-format dev-bundle zips — the `data/` + `patches/` +
`config.json` layout paperweight uses for paper/folia/custom forks
without distribution endpoints (data versions 2–8). Apply the plugin,
then declare the bundle on an env with `devbundle { }` (or
`legacydist { }` with `format = "devbundle"`):

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    plugins {
        id("xyz.wagyourtail.unimined.provider.devbundle")
    }

    unimined {
        minecraft {
            sourceSet("main") {
                devbundle {
                    file("myfork-dev-bundle.zip")
                    // url("https://example/bundle.zip", sha256 = "…") also works
                }
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    plugins {
        id 'xyz.wagyourtail.unimined.provider.devbundle'
    }

    unimined {
        minecraft {
            sourceSet("main") {
                devbundle {
                    file 'myfork-dev-bundle.zip'
                }
            }
        }
    }
    ```

The provider extracts the bundle's embedded mojang-mapped paperclip
jar and runs it (patch-only) against the vanilla `server.jar`
download, caching everything under the shared unimined cache. The
result is an already-patched, mojmap-named server jar — reported with
`namespaceMode = IDENTITY`, so no remap step runs on the dev
classpath.

What the bundle maps onto:

- **Artifact** — the patched server jar, keyed by `side`
  (`SERVER`/`MERGED`; `CLIENT` is rejected — bundles are
  server-only). `completedStages` reports `paperclip:binpatch`.
- **Libraries** — the bundle's declared api/compile/runtime
  coordinates plus the paperclip's own `META-INF/libraries.list`
  entries. Maven Central, Mojang Libraries, and Paper's repo are
  ensured; the bundle's own `libraryRepositories` are mirrored onto
  the project.
- **Mappings** — `server_mappings`/`client_mappings` channels serve
  the vanilla server-side Mojang map (the bundle's artifact is a
  server jar, so both keys point there), plus `reobf_mappings` /
  `access_transformer` aux artifacts when the bundle ships them
  (data versions ≤7 / ≤5 respectively) and `paperclip_jar` for the
  extracted tool.

Gaps and edges:

- `producer()` declared sources can't run inside the artifact
  transform — use `file()`/`url()`.
- The bundle's source `patches/` are not applied — the paperclip
  output is already patched.
- `launch` metadata is absent: Paper servers launch from the patched
  jar + libraries, not a version-JSON launch profile.
- `custom(name) { provider.set("devbundle"); file(...) }` is
  equivalent for forks that want a `custom:<name>` product id.

### Exporting a dev bundle

The same plugin is also the *producer*: every env gets a
`devBundleExport` spec and an `export<Env>DevBundle` task that writes
a consumable bundle (data version 6) of that env's production
artifact:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        minecraft {
            sourceSet("main") {
                devBundleExport {
                    apiCoordinates.set("com.example:myfork-api:1.0")
                    // serverJar.set(...)        // defaults to the env's
                    //                           // minecraftProd view
                    // outputZip.set(...)        // defaults to
                    //                           // build/devBundle/main-dev-bundle.zip
                }
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        minecraft {
            sourceSet("main") {
                devBundleExport {
                    apiCoordinates.set('com.example:myfork-api:1.0')
                }
            }
        }
    }
    ```

```bash
./gradlew exportDevBundle          # main env (export<Env>DevBundle otherwise)
# → build/devBundle/main-dev-bundle.zip — declare it on the consuming
#   side with devbundle { file("…") }
```

A unimined bundle is a *self-contained reproducer*: the env's
already-built prod jar rides inside `data/paperclip-mojang.jar`'s
`META-INF/versions/` entries and the paperclip's `Main-Class` is a
generated stand-in that extracts it — consumers get byte-identical
output to what the env built, no binary patching involved (so the zip
is larger than a patch-based bundle, and exact). `patches/` and
`reobf_mappings` carry through for source-level consumers when you
supply them (`sourcePatches`, `reobfMappingsFile`); the bundle is
otherwise deterministic — re-running the task on unchanged inputs
produces identical bytes.

What the export maps onto:

- **serverJar** — the env's `minecraftProd` view by default (the
  prod-namespace artifact through the whole JAR pipeline); point it
  elsewhere for a custom product.
- **Libraries** — the env's `minecraft<Env>Libraries` deps become
  `META-INF/libraries.list` rows *and* embed physically
  (`META-INF/libraries/`) so the bundle launches like a real
  paperclip; `embeddedLibraries.setFrom()` empties that for a
  coords-only slim bundle. `libraryRepositories` conventions to the
  project's declared maven repos.
- **Side** — dev bundles carry server artifacts; exporting a `CLIENT`
  env warns (a deliberate `serverJar` override may still be right),
  and a non-`mojmap` production namespace warns for the same reason.
- **Extras** — `apiCoordinates`/`mojangApiCoordinates` seed the
  consumer's compile classpath; `bundleId` names the bundler
  `version.json`/`versions.list` entry (`unimined-<env>-<version>`);
  `mainClass` writes `META-INF/main-class` for direct `java -jar`
  launch.
