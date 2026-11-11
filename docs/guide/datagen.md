# Datagen

`env.datagen()` declares an auxiliary environment for data
generation — separate sources, its own classpath, but sharing the
parent env's Minecraft/loader/mappings via implicit-parent
inheritance:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        minecraft {
            version = "1.20.1"
            loader("fabric", "0.16.0")

            datagen()                          // env "data" → runData
            // or: datagen(client = true)      // client-side pack
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        minecraft {
            version.set("1.20.1")
            loader("fabric", "0.16.0")

            datagen()                          // env "data" → runData
            // or: datagen("data", true)       // client-side pack
        }
    }
    ```

## What it wires

- `src/data/` (or `src/<env>Data/`) — your datagen entrypoints live
  here, compiled against the parent's classpath
- `src/<parent>/generated` is added to the parent env's **resources** —
  generated JSON/tags/recipes land in the mod jar automatically
- A `run<Data>` configuration materializes from the loader's
  `datagenMain`/`datagenArgs` capabilities — `{output}` in the arg
  template resolves to `src/<env>/generated`
- `client = true` appends the loader's `datagenClientArg`
  (fabric: `--client`) for client-side packs

The mod still declares its loader's datagen entrypoint itself
(e.g. fabric's `fabric-datagen` key in `fabric.mod.json`) — unimined
only provides the env, classpath, and run.

## Naming

`datagen()` defaults to `data` on `main`, `<env>Data` elsewhere;
`datagen("mygen")` overrides. It's an aux env (`isAuxEnv`) —
excluded from publication and loader-jar merging.

## Loaders without datagen

If the loader descriptor has no `datagenMain` capability, the run
still registers but launches the plain game — a warning is logged.
Forge supplies `datagenArgs` through its userdev capabilities;
older loaders need a manual `runs.named("data") { mainClass = … }`.
