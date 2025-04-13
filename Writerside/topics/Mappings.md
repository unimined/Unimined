# Mappings

Mappings provide the ability to remap minecraft into names you can actually use at runtime.
As well as remapping to the production mappings that each modloader uses.

## Supported mappings
this list may not always be up-to-date.
see [the api code](https://unimined.wagyourtail.xyz/unimined/%version%/api-docs/unimined/xyz.wagyourtail.unimined.api.mapping/-mappings-config/index.html) for the most up to date list.

### intermediaries
* intermediary (fabricmc)
* calamus (ornithemc)
* legacyIntermediary (legacyfabric)
* babricIntermediary (babric)
* searge (mcp)

### named
* yarn (fabricmc) (1.14-present)
* yarnv1 (fabricmc) (1.14-present) (recommended for 1.14-1.14.2 over yarn)
* mojmap (mojang) (1.14.4-present)
* mcp (mcp) (a1.2-1.16.5)
* retromcp (mcphackers) (a1.1-1.5.2)
* yarnv1 (fabricmc) (use for 1.14-1.14.2)
* feather (ornithemc) (old-1.14.4)
* legacyYarn (legacyfabric) (1.3-1.13.2)
* barn (babric) (b1.7.3)
* biny (babric) (b1.7.3)
* quilt (quiltmc) (1.18.2-present)
* forgeBuiltinMCP (forge/mcp) (1.2.5-1.6)
* parchment (parchment) (1.16.5-present)
* spigotDev (spigot) (1.8-present)
* unknownThingy


## Usage

Mappings are used in the `mappings` block of the `unimined.minecraft` block in your `build.gradle` file.
<tabs group="lang">
<tab id="Groovy-Mojmap" title="Groovy" group-key="groovy">

```groovy
unimined.minecraft {
    ...
    mappings {
        mojmap()
    }
    ...
}
```
</tab>
<tab id="Kotlin-Mojmap" title="Kotlin" group-key="kotlin">

```kotlin
unimined.minecraft {
    ...
    mappings {
        mojmap()
    }
    ...
}
```
</tab>
</tabs>

some require a version number, or version string to be passed in as a parameter.
<tabs group="lang">
<tab id="Groovy-Yarn" title="Groovy" group-key="groovy">

```groovy

unimined.minecraft {
    ...
    mappings {
        yarn 1
    }
    ...
}
```
</tab>
<tab id="Kotlin-Yarn" title="Kotlin" group-key="kotlin">

```kotlin

unimined.minecraft {
    ...
    mappings {
        yarn(1)
    }
    ...
}
```
</tab>
</tabs>

## Overriding mappings

it is sometimes useful to override specific mappings, such as when using forge with yarn mappings, or when a mod is using yarn
at dev, but you're using mojmap and it causes an issue.

unimined provides a DSL for inserting said mapping stubs, it's syntax is similar to umf's format.
<tabs group="lang">
<tab id="Groovy-Stub" title="Groovy" group-key="groovy">

```groovy
unimined.minecraft {
...
    mappings {
        ...
        stubs(["source", "target1", "target2"]) {
            c(["sourceName", "target1Name", "target2Name"]) {// can change the target names of a class, or use null/empty list to skip
                m(["sourceMethodName;(LsourceType;)LreturnType;", "target1MethodName"]) // can change the target names of a method, or use nulls/empty list to skip
            }
        }
    }
...
}
```
</tab>
<tab id="Kotlin-Stub" title="Kotlin" group-key="kotlin">

```kotlin
unimined.minecraft {
    ...
    mappings {
        ...
        stubs("source", "target1", "target2") {
            c("sourceName", "target1Name", "target2Name") { // can change the target names of a class, or use null/empty list to skip
                m("sourceMethodName;(LsourceType;)LreturnType;", "target1MethodName") // can change the target names of a method, or use nulls/empty list to skip
            }
        }
    }
    ...
}
```
</tab>
</tabs>

real example, (1.20.6, neoforge fix for yarn):

<tabs group="lang">
<tab id="Groovy-Stub-Real" title="Groovy" group-key="groovy">

```groovy
stubs(["intermediary", "yarn"]) {
    c(["net/minecraft/class_1496"]) {
        m(["method_56680;()Lnet/minecraft/class_1263;", "getInventoryVanilla"])
    }
    c(["net/minecraft/class_329"]) {
        m(["method_1759;(Lnet/minecraft/class_332;F)V", "renderHotbarVanilla"])
    }
}
```
</tab>
<tab id="Kotlin-Stub-Real" title="Kotlin" group-key="kotlin">

```kotlin
stubs("intermediary", "yarn") {
    c("net/minecraft/class_1496") {
        m("method_56680;()Lnet/minecraft/class_1263;", "getInventoryVanilla")
    }
    c("net/minecraft/class_329") {
        m("method_1759;(Lnet/minecraft/class_332;F)V", "renderHotbarVanilla")
    }
}
```
</tab>
</tabs>


## Custom mapping sources

you can add a custom mapping source with the `mapping` function inside the mappings block.

for example. to add yarn this way (for some reason) you would do:
<tabs group="lang">
<tab id="Groovy-Custom" title="Groovy" group-key="groovy">

```groovy
unimined.minecraft {
    ...
    mappings {
        ...
        mapping("net.fabricmc:yarn:1.17.1+build.1:v2", "yarn") {
            requires "intermediary" // sets the source namespace to intermediary
            mapNamespace("named", "yarn") // renames the output ns to "yarn"
            provides("yarn", true) // defines the output ns as yarn and is named

            renest() // causes classes that aren't named to be nested under their parent class's name,
            // ie, net.minecraft.class_1234$class_1235 -> net.minecraft.NamedNameFor1234$class_1235
        }
    }
    ...
}
```
</tab>
<tab id="Kotlin-Custom" title="Kotlin" group-key="kotlin">

```kotlin
unimined.minecraft {
    ...
    mappings {
        ...
        mapping("net.fabricmc:yarn:1.17.1+build.1:v2", "yarn") {
            requires("intermediary") // sets the source namespace to intermediary
            mapNamespace("named" to "yarn") // renames the output ns to "yarn"
            provides("yarn" to true) // defines the output ns as yarn and is named

            renest() // causes classes that aren't named to be nested under their parent class's name,
            // ie, net.minecraft.class_1234$class_1235 -> net.minecraft.NamedNameFor1234$class_1235
        }
    }
}
```
</tab>
</tabs>

## DevNamespace

you can define which named mappings to use in dev with the `devNamespace` field in the mappings block.
this also allows you to set the fallback namespace for if things aren't named in the dev namespace.

<tabs group="lang">
<tab id="Groovy-DevNamespace" title="Groovy" group-key="groovy">

```groovy
unimined.minecraft {
    ...
    mappings {
        ...
        devNamespace "mojmap" // sets the dev namespace to mojmap
    }
}
```
</tab>
<tab id="Kotlin-DevNamespace" title="Kotlin" group-key="kotlin">

```kotlin
unimined.minecraft {
    ...
    mappings {
        ...
        devNamespace("mojmap") // sets the dev namespace to mojmap
    }
}
```
</tab>
</tabs>