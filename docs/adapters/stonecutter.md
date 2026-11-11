# Stonecutter adapter

Plugin id: `xyz.wagyourtail.unimined.adapter-stonecutter`

Bridges [Stonecutter](https://codeberg.org/stonecutter/stonecutter)
into Unimined for multi-version projects. When
`dev.kikugie.stonecutter` is applied, every Unimined environment's
`version` conventions to the chiseled project's version
(`stonecutter.current.version`) — so the shared build script
doesn't repeat `version = stonecutter.current.version` in every
target. An explicit `version` on an environment always wins.

## Project layout

```text
my-mod/
├── settings.gradle.kts      # stonecutter plugin + version tree
├── build.gradle.kts         # central script — applied to every
│                            #   chiseled version project
├── gradle.properties
├── src/main/                # shared sources (java/kotlin/resources)
└── versions/
    ├── 1.20.4/
    │   └── gradle.properties  # per-version dep versions
    └── 1.21.1/
        └── gradle.properties
```

## settings.gradle.kts

=== "Kotlin `settings.gradle.kts`"
    ```kotlin
    plugins {
        id("dev.kikugie.stonecutter") version "0.9.8"
    }

    stonecutter {
        shared {
            versions("1.20.4", "1.21.1")
            vcsVersion = "1.21.1"
        }
        kotlinController = true
    }
    ```

=== "Groovy `settings.gradle`"
    ```groovy
    plugins {
        id 'dev.kikugie.stonecutter' version '0.9.8'
    }

    stonecutter {
        shared {
            versions("1.20.4", "1.21.1")
            vcsVersion = "1.21.1"
        }
        kotlinController = true
    }
    ```

## build.gradle.kts (central script)

Applied to every version project — the adapter supplies
`env.version` from the chiseled version, so this script stays
version-free:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    plugins {
        id("xyz.wagyourtail.unimined") version "..."
        id("xyz.wagyourtail.unimined.adapter-stonecutter") version "..."
        kotlin("jvm") version "..."
    }

    group = "com.example.mymod"

    unimined {
        minecraft {
            // env.version conventions to stonecutter.current.version —
            // "1.20.4" when building that project, "1.21.1" for the other
            loader("fabric", "0.16.0")
        }
    }

    dependencies {
        // per-version dep versions come from each version's
        // gradle.properties — stonecutter exposes them as `deps.*`
        // project properties
        modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
        modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    plugins {
        id 'xyz.wagyourtail.unimined' version '...'
        id 'xyz.wagyourtail.unimined.adapter-stonecutter' version '...'
        id 'org.jetbrains.kotlin.jvm' version '...' // TODO-groovy: kotlin plugin alias
    }

    group = "com.example.mymod"

    unimined {
        minecraft {
            // env.version conventions to stonecutter.current.version —
            // "1.20.4" when building that project, "1.21.1" for the other
            loader("fabric", "0.16.0")
        }
    }

    dependencies {
        // per-version dep versions come from each version's
        // gradle.properties — stonecutter exposes them as `deps.*`
        // project properties
        modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
        modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
    }
    ```

## versions/1.20.4/gradle.properties

```properties
deps.fabric_loader=0.16.9
deps.fabric_api=0.102.0+1.20.4
```

Per-version build-script overrides are also possible
(`versions/<ver>/build.gradle.kts`); the shared script still wins
only where it doesn't set explicit values.

## Multi-loader envs

Stonecutter wires `sourceSets.all { }`, so Unimined's env-per-
source-set model works per version project — each env's sources
get the same shared + version-specific layering and chisel
comment processing as `main`:

```text
my-mod/
└── src/
    ├── main/                # shared across loaders and versions
    ├── fabric/              # fabric env sources
    └── forge/               # forge env sources
versions/1.20.4/
└── src/
    ├── main/                # version-specific overrides
    └── forge/               # per-version, per-loader overrides
```

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    // central build.gradle.kts
    unimined {
        minecraft {
            loader("fabric", "0.16.0")            // main env

            sourceSet("forge") {
                loader("forge", "47.2.0")         // forge env — also
            }                                     //   chiseled per version
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    // central build.gradle
    unimined {
        minecraft {
            loader("fabric", "0.16.0")            // main env

            sourceSet("forge") {
                loader("forge", "47.2.0")         // forge env — also
            }                                     //   chiseled per version
        }
    }
    ```

Each version project then produces `jar` + `remapFabricJar` +
`remapForgeJar` for its own version — the full version×loader
matrix inside one chiseled project. Ordering is safe:
`sourceSets.all` is a live callback, so envs created after
Stonecutter's plugin is applied are still picked up. This
composes with `adapter-expectplatform`, `adapter-forgix`
(merges that version's loader jars), and
`adapter-modpublisher`.

Upstream Stonecutter templates sometimes encode the loader in
the *version id* instead (`versions("1.20.4-fabric")`) — that
also works but gives one project per loader×version pair; the
per-source-set layout above keeps all loaders for a version in
one project.

## Requirements

- **Java 21** — Stonecutter publishes Java-21 bytecode, so the
  adapter module targets 21 too (the rest of Unimined stays at 17)
- `dev.kikugie.stonecutter` must be applied; without it the
  adapter logs a warning and does nothing
