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
