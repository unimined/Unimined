# Modloaders

Unimined supports many modloaders — declared per-environment with
`loader("<name>", "<version>")` inside a `minecraft { }` env:

```kotlin
unimined {
    minecraft {
        loader("fabric", "0.16.0")
        loader("forge", "47.2.0") // repeat = multiloader env
    }
}
```

Names come from the loader registry: `fabric`, `quilt`, `forge`,
`neoforge`, `cleanroom`, `flint`, `rift`, `foxloader`, `liteloader`,
`craftbukkit`, `spigot`, `paper`, plus era loaders like `fg1` and
pseudo-loaders `jarmod`, `accessTransformer`, `accessWidener`.

Non-native products nest their loader inside the product env —
`unimined { bta { loader("bta-babric", "…") } }`,
`reindev { loader("foxloader", "…") }`.

Loader "api" artifacts resolve via `unimined.loaderApi(name, v)` /
`loaderApiModule(name, module, v)` — fabric-api, qsl, stationapi, osl —
which also records the api modid into `mod { depends }`.

For the most up-to-date registry, see the loader spec source.
