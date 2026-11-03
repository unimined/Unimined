# Standard Libraries

Loader "api" artifacts resolve through `unimined.loaderApi(...)` —
the 2.0 port of 1.x's `FabricLikeApiExtension` (`unimined.fabricApi`,
`quiltFabric`, `station`, `osl`, …):

```kotlin
dependencies {
    // whole api bundle → dependency coordinates
    modImplementation(unimined.loaderApi("fabric", "0.92.2+1.20.1"))
    // one sub-module
    modImplementation(unimined.loaderApiModule(
        "fabric", "fabric-api-base", "0.92.2+1.20.1"))
}
```

Registered providers: `fabric`, `quilt`, `qsl`, `legacyfabric`,
`stationapi`/`stationapi-snapshots`, `osl` (Ornithe resolves
per-MC-version modules via meta.ornithemc.net). Calling either helper
also records the api's modid into `mod { depends }` automatically.
