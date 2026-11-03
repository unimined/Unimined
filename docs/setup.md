# Setup

Add the following to the `settings.gradle`:

=== "Groovy"

    ```groovy
    pluginManagement {
        repositories {
            maven {
                url = uri("https://maven.wagyourtail.xyz/releases")
            }
            maven {
                url = uri("https://maven.wagyourtail.xyz/snapshots")
            }
            mavenCentral() // highly recommended, but not required
            gradlePluginPortal {
                content {
                    // this is not required either, unless jcenter goes down again, then it might fix things
                    excludeGroup("org.apache.logging.log4j")
                }
            }
        }
    }
    ```

=== "Kotlin"

    ```kotlin
    pluginManagement {
        repositories {
            maven("https://maven.wagyourtail.xyz/releases")
            maven("https://maven.wagyourtail.xyz/snapshots")
            mavenCentral() // highly recommended, but not required
            gradlePluginPortal {
                content {
                    // this is not required either, unless jcenter goes down again, then it might fix things
                    excludeGroup("org.apache.logging.log4j")
                }
            }
        }
    }
    ```


And the following to `build.gradle`:

=== "Groovy"

    ```groovy
    plugins {
        id 'xyz.wagyourtail.unimined' version '{{ plugin_version }}'
    }
    ```

=== "Kotlin"

    ```kotlin
    plugins {
        id("xyz.wagyourtail.unimined") version "{{ plugin_version }}"
    }
    ```


**This will not actually add minecraft to your project as it must be configured.**

## Adding Minecraft

to add minecraft to the main sourceSet, add the following:

=== "Groovy"

    ```groovy

    unimined.minecraft {
        version project.minecraftVersion
        side "combined" // default value

        mappings {
            mojmap()
        }

        fabric {
            loader project.fabricLoaderVersion
        }
    }

    ```

=== "Kotlin"

    ```kotlin
    val minecraftVersion: String by extra
    val fabricLoaderVersion: String by extra

    unimined.minecraft {
        version minecraftVersion
        side("combined") // default value

        mappings {
            mojmap()
        }

        fabric {
            loader(fabricLoaderVersion)
        }
    }

    ```


for more details about what's available within the `unimined.minecraft` block,
see [The Api Source](https://unimined.wagyourtail.xyz/unimined/{{ plugin_version }}/api-docs/unimined/xyz.wagyourtail.unimined.api.minecraft/-minecraft-config/index.html)
