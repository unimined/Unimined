# Loaders

Declare loaders per environment — `loader("<name>", "<version>")`,
repeat for multiloader:

```kotlin
minecraft {
    loader("fabric", "0.16.0")
    loader("forge", "47.2.0")
}
```

Loader names resolve through the loader registry:

--8<-- "generated/tables/loaders.md"

- **Forge is era-aware** — `loader("forge", …)` emits FG1-era wiring on
  ≤1.7.10, FML-style on 1.8–1.12, and modern Forge ≥1.13
  ([era examples](../generated/flat-forge-170.gradle.kts)).
- **Pseudo-loaders** — `jarmod` injects into the MC jar itself
  (see `--jarmod`/`--jars split|merged`); `accessTransformer`/
  `accessWidener` are transform-only.
- **Products nest loaders** — `bta { loader("bta-babric", …) }`.

Loader "api" artifacts resolve via `loaderApi` —
see [Dependencies](dependencies.md).
