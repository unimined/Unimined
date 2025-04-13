# Standard Libraries

Unimined provides helper functions for adding common standard libraries, such as fabric-api.

<tabs group="lang">
<tab id="Groovy-Setup-Standard-Libraries" title="Groovy" group-key="groovy">

```groovy

dependencies {
    modImplementation unimined.fabricModule("fabric-api-base", project.fabricApiVersion)
}

```

</tab>
<tab id="Kotlin-Setup-Standard-Libraries" title="Kotlin" group-key="kotlin">

```kotlin
val fabricApiVersion: String by extra

dependencies {
    modImplementation(unimined.fabricModule("fabric-api-base", fabricApiVersion))
}

```

</tab>
</tabs>

This is under `unimined` or `fabricApi` (for legacy reasons)

for a complete list of standard libraries supported, see [FabricLikeApi](https://unimined.wagyourtail.xyz/unimined/%version%/api-docs/unimined/xyz.wagyourtail.unimined.api.minecraft.patch.fabric/-fabric-like-api-extension/index.html)
