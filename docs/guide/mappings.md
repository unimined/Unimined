# Mappings

Each env declares a `mappings { }` chain — the sequence of mapping
namespaces the env's MC jar and mod deps get remapped through.

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        minecraft {
            mappings {
                yarn(10)              // fabric yarn
                // official()         // mojang official names
                // mcp("stable", "60") // MCP
                // searge()           // srg names
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        minecraft {
            mappings {
                yarn(10)              // fabric yarn
                // official()         // mojang official names
                // mcp("stable", "60") // MCP
                // searge()           // srg names
            }
        }
    }
    ```

Chain order matters — later entries layer onto earlier ones. The full
named-mapping catalog lives in the registry; generator-side, `--mappings`
takes the same DSL lines and `--var key=val` feeds mapping vars
(e.g. Ornithe's `ornitheGenVersion`).

Mod dependencies declared on env `mod*` configurations are remapped
through this chain automatically.

## Builtin channels

Beyond the channels shown above: `spigot()` (Spigot BuildData
mappings — fails loud if the provider can't supply the archive),
`parchment(mcVersion, version)` (Parchment param/javadoc layer),
`forgeBuiltinMCP(version)` (MCP data bundled in FG1 forge `:src`
zips), and `yarnv1(build)` — Tiny-v1 Yarn for legacy Fabric envs,
with stitch `proposeFieldNames` filled from the client jar.

## Generation-aware fabric-family channels

Ornithe's `calamus()`/`feather()` channels default to generation 1.
Set `ornitheGenVersion` to opt the environment into a newer generated
series, or pass `gen` on an individual declaration; gen2 uses the
`calamusGen2`/`featherGen2` namespaces and artifacts. Legacy Fabric's
`legacyIntermediary()`/`legacyYarn()` follow the same pattern through
`legacyFabricGenVersion`:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    mappings {
        ornitheGenVersion = 2
        calamus()
        feather(4)
        // per-declaration override: feather(4, gen = 1)
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    mappings {
        ornitheGenVersion = 2
        calamus()
        feather(4)
        // per-declaration override: feather(4, 1)
    }
    ```

The channel verbs relabel their documents' literal `named` column onto
the loader-specific namespace (`yarn`, `legacyYarn`, `barn`, `biny`,
`quilt`, and generation-suffixed variants) so several named sources can
compose without colliding. Feather nested-class names use the
`Outer__Inner` marker convention and are normalized to JVM
`Outer$Inner` names automatically while reading. Custom sources with
the same convention can opt in per source:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    mappings {
        maven("example:mappings:1.0") {
            named("exampleNamed")
            dollarInnerClasses() // or dollarInnerClasses("exampleNamed")
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    mappings {
        maven("example:mappings:1.0") {
            named("exampleNamed")
            dollarInnerClasses() // or dollarInnerClasses("exampleNamed")
        }
    }
    ```

## Ornithe enrichers

`nests(build)` (inner-class attributes), `sparrow(build)` (generic
signatures) and `raven(build)` (exception mappings) graft
`net.ornithemc` metadata artifacts onto the environment's official
columns — split-era merged versions emit one declaration per side.
Their elements apply during remap: nests restore `InnerClasses`
attributes for decompilers, signatures and exceptions feed the same
metadata path.

On `ornithe(…)` environments the latest published
`nests` build for the env's version + side is contributed to the
chain automatically — the equivalent of Ploceus's always-appended
nests mapping layer. An explicit `nests(...)` declaration wins over
the auto-picked build, and `disableNests()` on the environment
opts it out (the contribution also stays inert offline or when the
Ornithe meta index is unreachable). The meta base URL is
overridable with `-Punimined.ornitheMeta=<url>` for mirrors or
airgapped caches.

## Stub mappings

`stubs` declares inline mapping rows when a real channel doesn't
cover what you need — values are positional across the namespaces
you bind:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    mappings {
        stubs("official", "named") {
            c("a/b", "com/example/Foo") {
                f("a", "someField")
                m("c;()V", "run")
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    mappings {
        stubs(["official", "named"]) {
            c(["a/b", "com/example/Foo"]) {
                f(["a", "someField"])
                m(["c;()V", "run"])
            }
        }
    }
    ```

Member entries are `name[;desc]` per namespace; a `null` cell leaves
that namespace unmapped.

Set `customIntermediaries` on the mappings spec to tell
fabric-family loaders your intermediary column is your own, not
upstream's — their wiring checks this before contributing
`net.fabricmc:intermediary`:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    mappings {
        customIntermediaries.set(true)
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    mappings {
        customIntermediaries = true
    }
    ```

## Bytecode hierarchy fill

`hierarchyNameFill` scans the environment's raw Minecraft jar plus
its runtime libraries for the real class hierarchy, then propagates
mapped method and parameter names to overriding declarations the
chain leaves unmapped — synthesizing verbatim class/method cells for
tree-absent rows so remap does not break references (ploceus
`MappingFiller` parity; this *extends* the name copying
`fillMissingNames` performs on rows that already exist). The default
auto-enables when Ornithe enrichers (`nests`/`sparrow`/`raven`) are
declared; `true`/`false` forces it:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    mappings {
        feather(4)
        nests(4)
        hierarchyNameFill.set(true) // explicit — unset auto-enables on enrichers
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    mappings {
        feather(4)
        nests(4)
        hierarchyNameFill = true
    }
    ```

## Exporting

`uniminedMinecraft.exportMappings(envId) { }` registers an
`export<Env>Mappings` task (`exportMappings` for `main`) that writes
the env's resolved tree in a chosen format (SRG/TSRG/Tiny/MCP —
see `ExportMappingsTask` for the format + output inputs). The
pre-convention `exportMappings<Env>` name stays as a deprecated
alias.
