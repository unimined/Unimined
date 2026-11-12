# Installation

Unimined 2.0 is a Gradle plugin. Your `settings.gradle.kts` carries
three pieces:

- **`pluginManagement.repositories`** — where Gradle resolves the
  plugin itself (the `maven.wagyourtail.xyz` repos +
  `gradlePluginPortal()`). Required.
- **`dependencyResolutionManagement.repositories`** — settings-level
  dependency repos applied to every project that doesn't declare its
  own `repositories { }` (the generated module scripts do declare
  theirs, so this is a fallback). Under the default
  `PREFER_PROJECT` mode a project's own `repositories { }` wins —
  but you *can* centralize all repos here by switching
  `repositoriesMode` to `PREFER_SETTINGS` or
  `FAIL_ON_PROJECT_REPOS`, which makes the settings block the single
  source instead of repeating mavens per module.
- **`includeBuild("…")`** — optional, local composite development
  only (resolves the plugin from a source checkout instead of a
  maven artifact). Drop it for normal use.

=== "Kotlin `settings.gradle.kts`"
    ```kotlin
    --8<-- "generated/settings.gradle.kts"
    ```

=== "Groovy `settings.gradle`"
    ```groovy
    --8<-- "generated/settings.gradle"
    ```
Then apply the plugin in `build.gradle.kts`:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    plugins {
        id("xyz.wagyourtail.unimined") version "2.0.0-SNAPSHOT"
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    plugins {
        id 'xyz.wagyourtail.unimined' version '2.0.0-SNAPSHOT'
    }
    ```

!!! tip "Skip the typing"
    `unimined-cli new mymod --group com.example --version 1.20.1 --loader fabric=0.16.0`
    emits this whole structure for you — see [unimined-cli](cli.md).
