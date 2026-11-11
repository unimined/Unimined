# Gametests

`env.gametest()` declares an auxiliary environment for in-game
JUnit tests — real game launches orchestrated per test, not a plain
JVM `test` run:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        minecraft {
            version = "1.20.1"
            loader("fabric", "0.16.0")

            gametest()                         // env "gametest"
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        minecraft {
            version.set("1.20.1")
            loader("fabric", "0.16.0")

            gametest()                         // env "gametest"
        }
    }
    ```

## What it wires

- `src/gametest/` (or `src/<env>Gametest/`) — test sources on the
  parent's classpath via implicit-parent inheritance
- The loader's `gametestJunit` coordinate is added to
  `testImplementation` (`{loader}` in the template resolves to the
  env's loader version — e.g. fabric-loader-junit) and the env's
  `test` task is switched to `useJUnitPlatform()`
- **Client gametests**: the gametest env's own `<env>GametestClient`
  run gets the loader's `gametestClientJvmArgs` (`-D...` flags) —
  `{modid}` resolves against `mod { id }`. Entrypoint classes are
  declared by the loader (`fabric-gametest` / `fabric-client-gametest`
  keys in fabric.mod.json)
- Server gametests run inside the JUnit orchestration — no separate
  run needed

The mod still registers its loader-specific gametest entrypoints —
unimined provides the env, classpath, JUnit artifact, and the armed
client run.

## Loaders without gametest support

If the descriptor has no `gametestJunit` capability the env still
compiles (a warning notes the missing artifact) — write your own
`gametestImplementation` coordinate or orchestrate launches via a
custom `runs.create(...)`.

Aux env (`isAuxEnv`) — excluded from publication and merging.
