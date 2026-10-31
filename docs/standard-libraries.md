# Standard Libraries

Unimined provides helper functions for adding common standard libraries, such as fabric-api.

=== "Groovy"

    ```groovy

    dependencies {
        modImplementation unimined.fabricModule("fabric-api-base", project.fabricApiVersion)
    }

    ```

=== "Kotlin"

    ```kotlin
    val fabricApiVersion: String by extra

    dependencies {
        modImplementation(unimined.fabricModule("fabric-api-base", fabricApiVersion))
    }

    ```


This is under `unimined` or `fabricApi` (for legacy reasons)

for a complete list of standard libraries supported,
see [FabricLikeApi](https://unimined.wagyourtail.xyz/unimined/{{ plugin_version }}/api-docs/unimined/xyz.wagyourtail.unimined.api.minecraft.patch.fabric/-fabric-like-api-extension/index.html)
