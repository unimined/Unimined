# Multi-SourceSet / Multi-Project

## Multi-SourceSets

If you have multiple sourceSets that are seperate mods, but with the same minecraft configurations:

<tabs group="lang">
<tab id="Groovy-Multi-SourceSet" title="Groovy" group-key="groovy">

```groovy
unimined.minecraft([sourceSets.a, sourceSets.b]) {
    ...
}
```

</tab>
<tab id="Kotlin-Multi-SourceSet" title="Kotlin" group-key="kotlin">

```kotlin

val a by sourceSets.getting
val b by sourceSets.getting

unimined.minecraft(a, b) {
    ...
}
```

</tab>
</tabs>

for the same mod, but with multiple sourcesets

<tabs group="lang">
<tab id="Groovy-Multi-SourceSet-Same" title="Groovy" group-key="groovy">

```groovy
unimined.minecraft {
...
}

unimined.minecraft(sourceSets.a) {
    combineWith(sourceSets.main)
}
```
</tab>
<tab id="Kotlin-Multi-SourceSet-Same" title="Kotlin" group-key="kotlin">

```kotlin

unimined.minecraft {
    ...
}

val a by sourceSets.getting

unimined.minecraft(a) {
    combineWith(sourceSets.main.get())
}
```
</tab>
</tabs>

## Multi-Loader

this is an extension of the multi-sourcesets concept, but with different modloaders.

<tabs group="lang">
<tab id="Groovy-Multi-Loader" title="Groovy" group-key="groovy">

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

</tab>
<tab id="Kotlin-Multi-Loader" title="Kotlin" group-key="kotlin">

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
</tab>
</tabs>

## Multi-Project

multi-project is similar to multi-sourceSet, but with different projects.
this is useful for porting arch-loom projects. for new projects, it is recommended to not use this.

### main build gradle

<tabs group="lang">
<tab id="Groovy-Multi-Project" title="Groovy" group-key="groovy">

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
</tab>
<tab id="Kotlin-Multi-Project" title="Kotlin" group-key="kotlin">

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
</tab>
</tabs>

### common build.gradle

<tabs group="lang">
<tab id="Groovy-Common" title="Groovy" group-key="groovy">

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
    if (sourceSet == sourceSets.main) {
        defaultRemapJar = false
    }
}

// if not disabling remapJar above, 
// you may want to set this so the "common" jar is in intermediary to match architectury
tasks.named("remapJar") {
    prodNamespace("intermediary")
}
```
</tab>
<tab id="Kotlin-Common" title="Kotlin" group-key="kotlin">

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
    if (sourceSet == sourceSets.main.get()) {
        defaultRemapJar = false
    }
}

// if not disabling remapJar above,
// you may want to set this so the "common" jar is in intermediary to match architectury
tasks.named("remapJar") {
    prodNamespace("intermediary")
}
```
</tab>
</tabs>

### fabric build.gradle

<tabs group="lang">
<tab id="Groovy-Fabric" title="Groovy" group-key="groovy">

```groovy
unimined.minecraft {
    combineWith(":common:main") // combine with common, for identifying both together as one mod for dev runs 
    fabric {
        loader project.fabricLoaderVersion
        accessWidener "../common/src/main/resources/accessWidenerName.aw"
    }
}
```
</tab>
<tab id="Kotlin-Fabric" title="Kotlin" group-key="kotlin">

```kotlin
val fabricLoaderVersion: String by extra

unimined.minecraft {
    combineWith(":common:main") // combine with common, for identifying both together as one mod for dev runs 
    fabric {
        loader fabricLoaderVersion
        accessWidener("../common/src/main/resources/accessWidenerName.aw")
    }
}
```
</tab>
</tabs>

### forge build.gradle

<tabs group="lang">
<tab id="Groovy-Forge" title="Groovy" group-key="groovy">

```groovy
unimined.minecraft {
    combineWith(":common:main") // combine with common, for identifying both together as one mod for dev runs 
    minecraftForge {
        loader project.forgeVersion
        accessTransformer aw2at("../common/src/main/resources/accessWidenerName.aw")
    }
}
```
</tab>
<tab id="Kotlin-Forge" title="Kotlin" group-key="kotlin">

```kotlin
val forgeVersion: String by extra

unimined.minecraft {
    combineWith(":common:main") // combine with common, for identifying both together as one mod for dev runs 
    minecraftForge {
        loader forgeVersion
        accessTransformer aw2at("../common/src/main/resources/accessWidenerName.aw")
    }
}
```
</tab>
</tabs>
