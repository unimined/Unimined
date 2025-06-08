# Usage

Unimined is a per-source-set minecraft plugin for gradle.
To add the plugin to gradle,
add the following to settings.gradle:

## Setup

```gradle
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

and the following to build.gradle:

```gradle
plugins {
    id 'xyz.wagyourtail.unimined' version '1.2.6'
}
```

this will not actually add minecraft to your project as it must be configured.

## Adding Minecraft

to add minecraft to the main sourceSet, add the following:
```gradle

unimined.minecraft {
    version project.minecraftVersion
    // default value
    side "combined" 
    
    mappings {
        mojmap()
    }
    
    fabric {
        loader project.fabricLoaderVersion
    }
}

```

for more details about what's available within the unimined.minecraft block, see [The Api Source](/src/api/kotlin/xyz/wagyourtail/unimined/api/minecraft/MinecraftConfig.kt) or look below.

note that side `client` is not working in modern Forge.<sup>[ref](https://discord.com/channels/181078474394566657/603348502637969419/1380445184881594418)</sup>

## Mappings

Mappings provide the ability to remap minecraft into names you can actually use at runtime.
there are many mappings supported, see [Mappings](MAPPINGS.md) for more details.

## Modloaders

Unimined supports many modloaders, see [Modloaders](MODLOADERS.md) for more details.

## Standard Libraries

Unimined provides helper functions for adding common standard libraries, such as fabric-api.

```gradle

dependencies {
    modImplementation fabricApi.fabricModule("fabric-api-base", project.fabricApiVersion)
}

```

This is under `fabricApi`, even for other libraries for legacy reasons, and because I haven't thought of a better name.

for a complete list of standard libraries supported, see [FabricLikeApi](/src/api/kotlin/xyz/wagyourtail/unimined/api/minecraft/patch/fabric/FabricLikeApiExtension.kt)

## Remapping Mods

Unimined provides the ability to remap mods you depend on to the mappings you are using.
by default, unimined only implements `modImplementation`, but the others are easily creatable by the user.

For Example:
```gradle
configurations {
    modCompileOnly
    compileOnly.extendsFrom modCompileOnly
}

unimined.minecraft {
    ...
    mods {
        remap(configurations.modCompileOnly) {
        }
        
        // this is basically just a shortcut for `remap(configurations.modImplementation)`
        modImplementation {
            // you can do this is mods have the wrong access widener mapping, but it may break runs
            catchAWNamespaceAssertion()
        }
    }
}

dependencies {
    modCompileOnly "mod.group:mod.artifact:mod.version"
}
```

<details>
<summary>build.gradle.kts</summary>

you are recommended to declare them in this style if you are using kts.

```kts
val modCompileOnly: Configuration by configurations.creating
configurations.compileOnly.get().extendsFrom(modCompileOnly)

unimined.minecraft {
    ...
    mods {
        remap(modCompileOnly) {
        }
    }
}

// add this after unimined if you want to access `modImplementation` configuration
val modImplementation by configurations.getting
```

</details>

## Remapping Output

unimined provides a default `remapJar` task for each configuration, it may be useful to create an extra or custom remap task

```gradle

unimined.minecraft {
    ...
    defaultRemapJar = false // disable the default remapJar task
    
    remap(myJarTask) {
        prodNamespace("intermediary") // set the namespace to remap to
        mixinRemap {
            disableRefmap() // like fabric-loom 1.6
        }
    }
}

```

## Run Configurations

unimined provides a runServer and runClient task for each sourceSet by default.

you can configure/disable these as follows:
```gradle

unimined.minecraft {
    ...
    runs {
        off = true // disable all run configurations
        config("client") {
           disabled = true // disable the runClient task
           args += "--my-arg" // add an argument to the runClient task
        }
    }
}

```

for more details on what can be changed in the run configurations, see [RunConfig](/src/api/kotlin/xyz/wagyourtail/unimined/api/runs/RunConfig.kt)

### Auth
unimined provides a way to authenticate in your run configs.
the recommended way is to use gradle properties/env vars.

```properties
unimined.auth.enabled=true
unimined.auth.username=myUsername
```

This will open a web-browser to prompt for login when configuring and the token isn't cached.

Authentication is backed by the [MinecraftAuth](https://github.com/RaphiMC/MinecraftAuth) library.

Auth is also accessible under `runs.auth`.
for more information, see [AuthConfig](/src/api/kotlin/xyz/wagyourtail/unimined/api/runs/auth/AuthConfig.kt)
or it's implementation [AuthProvider](/src/runs/kotlin/xyz/wagyourtail/unimined/internal/runs/auth/AuthProvider.kt)

## Multi-SourceSet/Project

unimined can be configured seperately for each sourceSet, or in some more complicated cases.
for more details with complicated setups/multiple sourceSets: see [Multi-Sourceset/Project](MULTIPLE.md)