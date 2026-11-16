# Adapters

Adapters are opt-in Gradle plugins that bridge third-party tools
into Unimined. They live in `unimined-adapters/` and publish as
`xyz.wagyourtail.unimined:adapter-<tool>`.

They share a common shape:

- **Opt-in** — applying an adapter without its target tool is a
  no-op (or a logged warning); they activate via `withPlugin` when
  the tool's plugin is present.
- **Stable plugin ids** — adapter coordinates may move between
  artifacts, but `plugins {}` ids do not change.
- **Environment-scoped** — most adapters hang their DSL off each
  `sourceSet("...")`/environment block.

| Adapter | Plugin id | Bridges | Java |
|---|---|---|---|
| [JvmDowngrader](jvmdg.md) | `xyz.wagyourtail.unimined.jvmdg` | [JvmDowngrader](https://github.com/unimined/JvmDowngrader) bytecode downgrade | 17+ |
| [ModPublisher](modpublisher.md) | `xyz.wagyourtail.unimined.publish-modpublisher` | [ModPublisher](https://github.com/Hyperspace-Logistics-Group/ModPublisher) uploads | 17+ |
| [PatchBase](patchbase.md) | `xyz.wagyourtail.unimined.adapter-patchbase` | [PatchBase](https://github.com/unimined/PatchBase) patch sets | 17+ |
| [Forgix](forgix.md) | `xyz.wagyourtail.unimined.adapter-forgix` | [Forgix](https://github.com/PacifistMC/Forgix) loader-jar merge | 17+ |
| [Stonecutter](stonecutter.md) | `xyz.wagyourtail.unimined.adapter-stonecutter` | [Stonecutter](https://codeberg.org/stonecutter/stonecutter) version envs | **21+** |
| [ExpectPlatform](expectplatform.md) | `xyz.wagyourtail.unimined.adapter-expectplatform` | [ExpectPlatform](https://github.com/unimined/ExpectPlatform) agent | 17+ |

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    plugins {
        id("xyz.wagyourtail.unimined")
        id("xyz.wagyourtail.unimined.adapter-patchbase") version "2.0.0"
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    plugins {
        id 'xyz.wagyourtail.unimined'
        id 'xyz.wagyourtail.unimined.adapter-patchbase' version '2.0.0'
    }
    ```

## Upstream versions

Adapters couple to their tools in three ways — each has its own
knob for running a newer upstream release than the one Unimined
shipped against, so a minor upstream update rarely requires a
Unimined update:

| Coupling | How it works | Version knob |
|---|---|---|
| **Detected plugin** | adapter hooks `withPlugin(id)` and reads the upstream extension when you apply it | bump the version in your own `plugins {}` block — the adapter detects whatever you resolve |
| **Resolved artifact** | engine/tool jars resolved from a named configuration at execution | override the coordinate on `uniminedTools`, or declare a dependency on the adapter's configuration directly |
| **In-process engine** | engine classes linked on the adapter's classpath | Gradle dependency substitution on the plugin classpath — bounded by binary compatibility of the entry points the adapter calls |

Per-adapter defaults and overrides:

| Adapter | Bundled default | How to override |
|---|---|---|
| [Forgix](forgix.md) | `uniminedTools.forgix` → `io.github.pacifistmc.forgix:Forgix:2.0.0` | `uniminedTools { forgix.set("...:2.1.0") }` or a `loaderMergeForgix` dep — resolved into an isolated classloader, so the override *is* the merge implementation |
| [JvmDowngrader](jvmdg.md) | `uniminedTools.jvmdowngraderJavaApi` → `jvmdowngrader-java-api:2.0.1` | `uniminedTools { jvmdowngraderJavaApi.set("...:2.1.0") }` for the stub jar; engine bumps via applying `xyz.wagyourtail.jvmdowngrader` yourself or classpath substitution |
| [PatchBase](patchbase.md) | none — patch-set coords are yours | version lives in the `patchBase("g:a:v")` declaration |
| [Stonecutter](stonecutter.md) | none — adapter detects | `plugins { id("dev.kikugie.stonecutter") version "..." }` |
| [ExpectPlatform](expectplatform.md) | none — adapter detects | `plugins { id("xyz.wagyourtail.unimined.expect-platform") version "..." }` |
| [ModPublisher](modpublisher.md) | none — adapter detects | `plugins { id("com.hypherionmc.modutils.modpublisher") version "..." }` |

Where an adapter resolves its engine reflectively through an isolated
classloader (currently just Forgix's `mergeLoaders`), a bumped
coordinate is all that's needed — incompatible versions fail the task
with a clear error naming the missing entry point rather than a
`NoSuchMethodError`.

=== "Kotlin `build.gradle.kts` — overriding a tool coordinate"
    ```kotlin
    uniminedTools {
        forgix.set("io.github.pacifistmc.forgix:Forgix:2.1.0")
    }
    ```

=== "Groovy `build.gradle` — overriding a tool coordinate"
    ```groovy
    uniminedTools {
        forgix.set('io.github.pacifistmc.forgix:Forgix:2.1.0')
    }
    ```

=== "Kotlin `build.gradle.kts` — classpath substitution escape hatch"
    ```kotlin
    // settings.gradle.kts (or a buildscript block)
    configurations.classpath {
        resolutionStrategy.dependencySubstitution {
            substitute(module("io.github.pacifistmc.forgix:Forgix"))
                .using(module("io.github.pacifistmc.forgix:Forgix:2.1.0"))
        }
    }
    ```
