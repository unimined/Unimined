# Adapters

Adapters are opt-in Gradle plugins that bridge third-party tools
into Unimined. They live in `unimined-adapters/` and publish as
`xyz.wagyourtail.unimined:unimined-adapter-<tool>`.

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
