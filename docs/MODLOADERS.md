# Modloaders

## Unimined supports many modloaders
this list may not always be up-to-date.
for the most up-to-date list, see [the api code](/src/api/kotlin/xyz/wagyourtail/unimined/api/minecraft/PatchProviders.kt).

* minecraftForge
* neoForged
* fabric
* flint
* quilt
* rift
* craftbukkit
* spigot

it also supports several *pseudo* modloaders through:
* jarmod
* accessTransformer
* accessWidener

## Usage

Modloaders are used in their own block of the `unimined.minecraft` block in your `build.gradle` file.
```gradle

unimined.minecraft {
...
    minecraftForge {
        loader project.forgeVersion
    }
...
}
```

for legacy reasons, the version number is always provided in a field named "loader".

## AccessTransformer/AccessWidener

you can provide an access transformer or access widener to be applied to the minecraft jar if the selected 

```gradle
unimined.minecraft {
...
    fabric {
        ...
        accessWidener "src/main/resources/modid.accesswidener"
    }
...
}
```

## Mixins on Forge

you need to declare the mixin configuration json for Forge.
the configuration file should be placed in resources with the provided name.

```gradle
...
    minecraftForge {
        ...
        mixinConfig "example_mod.mixins.json"
    }
...
```

# Custom Modloaders

you can create a custom modloader by implementing the [MinecraftPatcher](/src/api/kotlin/xyz/wagyourtail/unimined/api/minecraft/patch/MinecraftPatcher.kt) interface.
For example, this is done by [Prcraft](https://github.com/prcraft-minecraft/PrcraftExampleMod/blob/main/buildSrc/src/main/kotlin/xyz/wagyourtail/unimined/minecraft/patch/prcraft/PrcraftMinecraftTransformer.kt)
