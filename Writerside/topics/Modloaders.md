# Modloaders

## Unimined supports many modloaders
this list may not always be up-to-date.
for the most up-to-date list, see [the api code](https://unimined.wagyourtail.xyz/unimined/%version%/api-docs/unimined/xyz.wagyourtail.unimined.api.minecraft/-patch-providers/index.html).

* minecraftForge
* neoForged
* fabric
* flint
* quilt
* rift
* craftbukkit
* spigot
* paper
* liteloader

### it also supports several *pseudo* modloaders:
* jarmod
* accessTransformer
* accessWidener

## Usage

Modloaders are used in their own block of the `unimined.minecraft` block in your `build.gradle` file.
<tabs group="lang">
<tab id="Groovy-Modloaders" title="Groovy" group-key="groovy">

```groovy

unimined.minecraft {
    ...
    minecraftForge {
        loader project.forgeVersion
    }
    ...
}
```

</tab>
<tab id="Kotlin-Modloaders" title="Kotlin" group-key="kotlin">

```kotlin

val forgeVersion: String by extra

unimined.minecraft {
    ...
    minecraftForge {
        loader(forgeVersion)
    }
    ...
}
```
</tab>
</tabs>

for legacy reasons, the version number is almost always provided in a field named "loader".

### AccessTransformer/AccessWidener

you can provide an access transformer or access widener to be applied to the minecraft jar if the selected

<tabs group="lang">
<tab id="Groovy-AccessWidener" title="Groovy" group-key="groovy">

```groovy
unimined.minecraft {
    ...
    fabric {
        ...
        accessWidener "src/main/resources/modid.accesswidener"
    }
    ...
}
```

</tab>
<tab id="Kotlin-AccessWidener" title="Kotlin" group-key="kotlin">

```kotlin

unimined.minecraft {
    ...
    fabric {
        ...
        accessWidener("src/main/resources/modid.accesswidener")
    }
    ...
}
```
</tab>
</tabs>

## Custom Modloaders

you can create a custom modloader by implementing the [MinecraftPatcher](https://unimined.wagyourtail.xyz/unimined/%version%/api-docs/unimined/xyz.wagyourtail.unimined.api.minecraft.patch/-minecraft-patcher/index.html) interface.
For example, this is done by [Prcraft](https://github.com/prcraft-minecraft/PrcraftExampleMod/blob/main/buildSrc/src/main/kotlin/xyz/wagyourtail/unimined/minecraft/patch/prcraft/PrcraftMinecraftTransformer.kt)
