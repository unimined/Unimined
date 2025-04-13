# About Unimined

Welcome to Unimined, a unified Minecraft modding environment that supports both modern
and legacy environments. Whether you're working with the latest Minecraft versions or
exploring older builds, Unimined provides a comprehensive set of tools to help you
build mods with ease.

## Features
* **Unified Modding Environment:** Supports various Minecraft mod loaders and offers tools for a seamless experience across different modding platforms.
* **Custom Loader Support:** Create and integrate your own loaders with the flexibility of Unimined.

## Supported Loaders
* [Fabric](https://fabricmc.net/)
* [Quilt](https://quiltmc.org/)
* [Forge](https://minecraftforge.net/)
* [Neoforge](https://neoforged.net/)
* just plain jarmodding
    * [Modloader](https://mcarchive.net/mods/modloader)
    * [JarModAgent](https://github.com/unimined/JarModAgent)
* [Flint Loader](https://flintloader.net)
* Rift
* [Craftbukkit/Spigot](https://hub.spigotmc.org)
* [Paper](https://papermc.io)
* [Liteloader](https://liteloader.com)

### Planned
* Better Bukkit-derivitive support
    * Spigot currently just kinda runs buildtools in the backround.
* Sponge
* NilLoader

### Custom Loaders
you can create a custom patching/transforming process for minecraft,
for example [PrcraftExampleMod](https://github.com/prcraft-minecraft/PrcraftExampleMod)'s buildsrc dir.


## Planned Features
* Eclipse / VSCode Support
* Datagen
* Fixing neoforge on yarn (again)

## Recommended Setup

you can either re-create a unimined build from a standard gradle project or,

1. take one of the versions from [testing](https://github.com/unimined/Unimined/tree/main/testing)
1. remove `includeBuild('../../')` from `settings.gradle`
1. put a proper version number for the plugin in `build.grade`

## Other Setups

### Arch-Loom Style
* direct porting of arch-loom projects without changing the directory structure is possible.
* instructions pending...
### third party template(s)
* arch style: https://github.com/firstdarkdev/fdd-xplat or https://github.com/LegacyModdingMC/examplemod
