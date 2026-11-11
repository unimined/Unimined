# Multiple Source Sets & Projects

> 2.0 rewrite. The 1.x `unimined.minecraft(sourceSets.x)` API moved —
> envs live in `minecraft { sourceSet(...) { } }`; see
> [Migrating from 1.x](../migration-1x-to-2.0.md).

Two shapes cover the 1.x use cases:

- **flat** — several `sourceSet` envs in one project;
- **multi-module** — a `common` module + one subproject per loader
  (what `unimined-cli new --modules` generates).

## Flat: one env per source set

Each `minecraft { sourceSet(...) { } }` block is an independent
environment — its own product, loader, mappings, runs:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        minecraft {
            loader("fabric", "0.16.0")            // env "main"

            sourceSet("forge") {
                version = "1.20.1"
                loader("forge", "47.2.0")
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        minecraft {
            loader("fabric", "0.16.0")            // env "main"

            sourceSet("forge") {
                version.set("1.20.1")
                loader("forge", "47.2.0")
            }
        }
    }
    ```

### Sharing between envs

Envs are independent by default; three mechanisms share
configuration and classpath:

- **Implicit parent** — an env declared *inside* another env's
  block (`sourceSet("datagen") { datagen() }` style nesting, and
  the `datagen`/`gametest` aux verbs) inherits the parent's
  sections automatically. Opt out with `inheritParent.set(false)`.
- **`combineWith(...)`** — pull another env's declarations in
  explicitly: `combineWith("main")`, `combineWith("common", env)`
  with `sections` to take only some (mappings, runs, deps, …).
- **Module deps** — `modImplementation(project(":common"))`-style
  references share code across projects.

Sibling `sourceSet` envs at `minecraft {}` top level don't share —
`combineWith("main")` is the way to layer the main env's config in.

## Multi-module: common + per-loader subprojects

This is what `unimined new mymod --modules --loader fabric=…
--loader forge=…` emits. Settings includes every module:

=== "Kotlin `settings.gradle.kts`"
    ```kotlin
    --8<-- "generated/multiloader-settings.gradle.kts"
    ```

=== "Groovy `settings.gradle`"
    ```groovy
    --8<-- "generated/multiloader-settings.gradle"
    ```
A loader module applies unimined, declares its env on `main`, and
depends on `:common` for shared code:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    --8<-- "generated/multiloader-fabric.gradle.kts"
    ```

=== "Groovy `build.gradle`"
    ```groovy
    --8<-- "generated/multiloader-fabric.gradle"
    ```
`common` gets a loader-less env (MC classpath + mappings only); the
loader jars land as `modImplementation` deps in each loader module.
Cross-project inheritance uses the `<project>:<env>` path form —
`combineWith(":common:main")` pulls the `common` project's `main`
env (classpath included, so shared code resolves); `copyFrom`
reuses sections without composing output.
