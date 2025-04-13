# Setup

<tabs group="lang">
<tab id="Groovy-Setup-Settings" title="Groovy" group-key="groovy">

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

</tab>
<tab id="Kotlin-Setup-Settings" title="Kotlin" group-key="kotlin">

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
</tab>
</tabs>

and the following to build.gradle:

<tabs group="lang">
<tab id="Groovy-Setup" title="Groovy" group-key="groovy">

```groovy
plugins {
    id 'xyz.wagyourtail.unimined' version '%version%'
}
```

</tab>
<tab id="Kotlin-Setup" title="Kotlin" group-key="kotlin">

```kotlin
plugins {
    id("xyz.wagyourtail.unimined") version "%version%"
}
```
</tab>
</tabs>

this will not actually add minecraft to your project as it must be configured.

## Adding Minecraft

to add minecraft to the main sourceSet, add the following:

<tabs group="lang">
<tab id="Groovy-Setup-Main" title="Groovy" group-key="groovy">

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

</tab>
<tab id="Kotlin-Setup-Main" title="Kotlin" group-key="kotlin">

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
</tab>
</tabs>

for more details about what's available within the `unimined.minecraft` block, see [The Api Source](https://unimined.wagyourtail.xyz/unimined/%version%/api-docs/unimined/xyz.wagyourtail.unimined.api.minecraft/-minecraft-config/index.html)
