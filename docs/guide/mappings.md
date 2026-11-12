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

Set `customIntermediaries = true` on the mappings spec to tell
fabric-family loaders your intermediary column is your own, not
upstream's — their wiring checks this before contributing
`net.fabricmc:intermediary`.

## Exporting

`uniminedMinecraft.exportMappings(envId) { }` registers an
`exportMappings<Env>` task (`exportMappings` for `main`) that writes
the env's resolved tree in a chosen format (SRG/TSRG/Tiny/MCP —
see `ExportMappingsTask` for the format + output inputs).
