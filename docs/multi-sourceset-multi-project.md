# Multi-SourceSet / Multi-Project

## Multi-SourceSets

If you have multiple sourceSets that are seperate mods, but with the same minecraft configurations:

=== "Groovy"

    ```groovy
    unimined.minecraft([sourceSets.a, sourceSets.b]) {
        ...
    }
    ```

=== "Kotlin"

    ```kotlin

    val a by sourceSets.getting
    val b by sourceSets.getting

    unimined.minecraft(a, b) {
        ...
    }
    ```


for the same mod, but with multiple sourcesets

=== "Groovy"

    ```groovy
    unimined.minecraft {
    ...
    }

    unimined.minecraft(sourceSets.a) {
        combineWith(sourceSets.main)
    }
    ```

=== "Kotlin"

    ```kotlin

    unimined.minecraft {
        ...
    }

    val a by sourceSets.getting

    unimined.minecraft(a) {
        combineWith(sourceSets.main.get())
    }
    ```


## Multi-Loader

this is an extension of the multi-sourcesets concept, but with different modloaders.

=== "Groovy"

    ```groovy
    // main as common
    unimined.minecraft {
        version project.mcVersion

        mappings {
           mojmap()
        }

        accessWidener {
            accessWidener "src/main/resources/accessWidenerName.aw"
        }

        // you may want to set this if you want to include architectury mods in common
        mods.modImplementation {
            namespace("intermediary")
        }

        // if you don't want to build/remap a "common" jar
        if (sourceSet == sourceSets.main) {
            defaultRemapJar = false
        }
    }

    // if not disabling remapJar above, 
    // you may want to set this so the "common" jar is in intermediary to match architectury
    tasks.named("remapJar") {
        prodNamespace("intermediary")
    }

    // forge
    unimined.minecraft(sourceSets.forge) {
        combineWith(sourceSets.main)
        minecraftForge {
            loader project.forgeVersion
            accessTransformer aw2at("src/main/resources/accessWidenerName.aw")
        }
    }

    // fabric
    unimined.minecraft(sourceSets.fabric) {
        combineWith(sourceSets.main)
        fabric {
            loader project.fabricLoaderVersion
            accessWidener "src/main/resources/accessWidenerName.aw"
        }
    }
    ```

=== "Kotlin"

    ```kotlin

    val mcVersion: String by extra
    val forgeVersion: String by extra
    val fabricLoaderVersion: String by extra

    val forge by sourceSets.creating
    val fabric by sourceSets.creating

    // main as common
    unimined.minecraft {
        version(mcVersion)

        mappings {
           mojmap()
        }

        accessWidener {
            accessWidener("src/main/resources/accessWidenerName.aw")
        }

        // you may want to set this if you want to include architectury mods in common
        mods.modImplementation {
            namespace("intermediary")
        }

        // if you don't want to build/remap a "common" jar
        if (sourceSet == sourceSets.main.get()) {
            defaultRemapJar = false
        }
    }

    // if not disabling remapJar above,
    // you may want to set this so the "common" jar is in intermediary to match architectury
    tasks.named("remapJar") {
        prodNamespace("intermediary")
    }

    // forge
    unimined.minecraft(forge) {
        combineWith(sourceSets.main.get())
        minecraftForge {
            loader(forgeVersion)
            accessTransformer("src/main/resources/accessWidenerName.aw")
        }
    }

    // fabric
    unimined.minecraft(fabric) {
        combineWith(sourceSets.main.get())
        fabric {
            loader(fabricLoaderVersion)
            accessWidener("src/main/resources/accessWidenerName.aw")
        }
    }

    ```


## Multi-Project

multi-project is similar to multi-sourceSet, but with different projects.
this is useful for porting arch-loom projects. for new projects, it is recommended to not use this.

### main build gradle

=== "Groovy"

    ```groovy
    ...
    subprojects {
        ...
        unimined.minecraft(sourceSets.main, true) { // the true here defers loading until the next time unimined.minecraft is called, (in each subproject's build.gradle)
            mappings {
               mojmap()
            }
        }
    }

    ```

=== "Kotlin"

    ```kotlin
    ...
    subprojects {
        ...
        unimined.minecraft(sourceSets.main, true) { // the true here defers loading until the next time unimined.minecraft is called, (in each subproject's build.gradle)
            mappings {
               mojmap()
            }
        }
    }

    ```


### common build.gradle

=== "Groovy"

    ```groovy
    unimined.minecraft {
        accessWidener {
            accessWidener "src/main/resources/accessWidenerName.aw"
        }

        // you may want to set this if you want to include arch mods in common
        mods.modImplementation {
            namespace "intermediary"
        }

        // if you don't want to build/remap a "common" jar
        if (project.name == "common") {
            defaultRemapJar = false
        }
    }

    // if not disabling remapJar above, 
    // you may want to set this so the "common" jar is in intermediary to match architectury
    tasks.named("remapJar") {
        prodNamespace("intermediary")
    }
    ```

=== "Kotlin"

    ```kotlin
    unimined.minecraft {
        accessWidener {
            accessWidener("src/main/resources/accessWidenerName.aw")
        }

        // you may want to set this if you want to include arch mods in common
        mods.modImplementation {
            namespace("intermediary")
        }

        // if you don't want to build/remap a "common" jar
        if (project.name == "common") {
            defaultRemapJar = false
        }
    }

    // if not disabling remapJar above,
    // you may want to set this so the "common" jar is in intermediary to match architectury
    tasks.named("remapJar") {
        prodNamespace("intermediary")
    }
    ```


### fabric build.gradle

=== "Groovy"

    ```groovy
    unimined.minecraft {
        combineWith(":common:main") // combine with common, for identifying both together as one mod for dev runs 
        fabric {
            loader project.fabricLoaderVersion
            accessWidener "../common/src/main/resources/accessWidenerName.aw"
        }
    }
    ```

=== "Kotlin"

    ```kotlin
    val fabricLoaderVersion: String by extra

    unimined.minecraft {
        combineWith(":common:main") // combine with common, for identifying both together as one mod for dev runs 
        fabric {
            loader(fabricLoaderVersion)
            accessWidener("../common/src/main/resources/accessWidenerName.aw")
        }
    }
    ```


### forge build.gradle

=== "Groovy"

    ```groovy
    unimined.minecraft {
        combineWith(":common:main") // combine with common, for identifying both together as one mod for dev runs 
        minecraftForge {
            loader project.forgeVersion
            accessTransformer aw2at("../common/src/main/resources/accessWidenerName.aw")
        }
    }
    ```

=== "Kotlin"

    ```kotlin
    val forgeVersion: String by extra

    unimined.minecraft {
        combineWith(":common:main") // combine with common, for identifying both together as one mod for dev runs 
        minecraftForge {
            loader(forgeVersion)
            accessTransformer aw2at("../common/src/main/resources/accessWidenerName.aw")
        }
    }
    ```

