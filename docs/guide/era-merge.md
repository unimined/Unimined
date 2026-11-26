# Era Merge Packaging

`mergedEraJar` and `chimeraJar` package several environment production jars
into one distribution artifact. Both use the same merge machinery:

- `mergedEraJar` is for one loader across several Minecraft versions;
- `chimeraJar` is for several loaders/eras in one artifact.

Era selection defaults to bound, versioned, non-auxiliary environments with
`defaultRemapJar` enabled. Inert shared environments such as versionless
`main` are not themselves eras. Calling `eras(...)` replaces the automatic set
exactly and preserves the supplied merge order.

=== "Kotlin"

    ```kotlin title="build.gradle.kts"
    unimined {
        mod {
            chimeraJar {
                // Optional; omit to use automatic output-environment selection.
                eras("fabric112", "fabric471", "bta", "forge147")

                era("bta") {
                    partition.set("flatten") // override the fabric-family default
                }

                nested {
                    id.set("example-inner")
                    parent.set("example")
                    nestedEntry("META-INF/example-stub.jar")
                    manifest {
                        attribute("Calamus-Generation", "2")
                    }
                }

                manifest {
                    attribute("MixinConfigs", "example.mixins.json")
                    attribute("FMLCorePluginContainsFMLMod", true)
                }
            }
        }
    }
    ```

=== "Groovy"

    ```groovy title="build.gradle"
    unimined {
        mod {
            chimeraJar {
                eras('fabric112', 'fabric471', 'bta', 'forge147')

                era('bta') {
                    partition.set('flatten')
                }

                nested {
                    id.set('example-inner')
                    parent.set('example')
                    nestedEntry('META-INF/example-stub.jar')
                    manifest {
                        attribute('Calamus-Generation', '2')
                    }
                }

                manifest {
                    attribute('MixinConfigs', 'example.mixins.json')
                    attribute('FMLCorePluginContainsFMLMod', true)
                }
            }
        }
    }
    ```

`mergedEraJar` uses the same settings and produces the `-merged` classifier;
`chimeraJar` produces `-chimera`. Configuring either verb attaches that task
to `assemble`.

## Merge layout

When `mainFirst` is enabled (the default), output inherited through
`combineWith` is written first as raw source-set output. Era production
artifacts then follow in era order. Duplicate jar-relative paths are
first-wins. This deliberately tolerates era jars built while `combineWith`
still uses shadow-style embedding: the embedded shared copy is deduplicated,
while overlap diagnostics point at inputs that have not yet moved to era-only
packaging.

Fabric-family eras are nested by default because their loader metadata
declares FMJ-style nested-jar discovery. Other loaders flatten into the outer
jar. Override a single era explicitly:

=== "Kotlin"

    ```kotlin
    era("quilt112") { partition.set("flatten") }
    era("forge147") { partition.set("nest") }
    ```

=== "Groovy"

    ```groovy
    era('quilt112') { partition.set('flatten') }
    era('forge147') { partition.set('nest') }
    ```

Valid values are `"nest"` and `"flatten"`.

Nested content is merged into one inner jar, written by default at
`META-INF/jars/<project-name>-nested.jar` inside the outer artifact (override
with `nested.path`). The helper emits a minimal shell `fabric.mod.json` in the
inner jar unless `nested.emitDescriptor` is false:

- `schemaVersion = 1`
- `id`, `version`, and ModMenu parent metadata
- `jars[]` object entries for every `nestedEntry(...)`/`nestedJar(...)` path

`nestedEntry(path)` only records a metadata entry. `nestedJar(path, source)`
records the entry and physically embeds `source` at that path. `source` uses
normal Gradle file semantics, so it may be a file, configuration, or task
output provider.

## Overlap validation

`overlapValidation` is stringly typed:

- `"warn"` (default): log the first duplicate owner and the discarded input;
- `"strict"`: fail before writing an output jar;
- `"off"`: first-wins without diagnostics.

Generated shell metadata and explicitly supplied additional nested jars are
authoritative and therefore do not count as accidental overlap. Duplicate
manifest attributes are also first-wins. User-specified manifest attributes
are written last and always override input attributes.

=== "Kotlin"

    ```kotlin
    overlapValidation.set("strict")
    mainFirst.set(false) // opt out of prepending combineWith output
    ```

=== "Groovy"

    ```groovy
    overlapValidation.set('strict')
    mainFirst.set(false) // opt out of prepending combineWith output
    ```

## Manual or unusual era artifacts

The default artifact for an environment is `remap<Env>Jar`, or the plain env
jar for identity-remap environments. A manual artifact can replace that, and
a jar-backed name can even participate without a bound environment:

=== "Kotlin"

    ```kotlin
    eras("special")
    era("special") {
        jar.set(layout.buildDirectory.file("custom/special.jar"))
        loader.set("fabric") // used for the default nest/flatten decision
    }
    ```

=== "Groovy"

    ```groovy
    eras('special')
    era('special') {
        jar.set(layout.buildDirectory.file('custom/special.jar'))
        loader.set('fabric') // used for the default nest/flatten decision
    }
    ```

Keep loader-specific requirements in mind: jarjar metadata generation for
modern Forge, per-era loader descriptor generation, version-range activation
metadata, and automatic divergence/conflict resolution are intentionally not
part of this v1 helper.
