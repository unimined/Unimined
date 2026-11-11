# Forgix (loader-jar merge)

Adapter plugin `xyz.wagyourtail.unimined.adapter-forgix`
(`unimined-adapter-forgix`) merges per-loader production jars with
[Forgix](https://github.com/PacifistMC/Forgix)'s engine
(`Forgix.mergeLoaders`) and publishes the result on the consumable
`mergedElements` configuration (classifier `merged`).

## Project layout

```text
my-mod/
├── build.gradle.kts
└── src/
    ├── main/         # common env
    ├── fabric/       # fabric env → remapFabricJar
    └── forge/        # forge env → remapForgeJar
```

## build.gradle.kts

```kotlin
plugins {
    id("xyz.wagyourtail.unimined") version "..."
    id("xyz.wagyourtail.unimined.adapter-forgix") version "..."
}

unimined {
    minecraft {
        version = "1.20.1"
        loader("fabric", "0.16.0")

        sourceSet("forge") {
            version = "1.20.1"
            loader("forge", "47.2.0")
        }
    }
}
```

That's the whole configuration — `mergeLoaderJars` discovers every
bound non-auxiliary environment's production jar automatically.
A typical multi-loader project writes **zero** merge config.

Forgix's own Gradle plugin is not required — its `merge(name)` DSL
and `mergeJars` task only support multi-project layouts (merge names
must be subproject names, and its jar-resolution heuristic checks the
project before `inputJar`), so flat env-per-source-set projects can't
use it. The adapter drives the layout-agnostic engine directly from
`mergeLoaderJars` instead.

## Inputs

Three sources feed the merge, in increasing precedence:

1. **Bound environments — auto-injected.** Every non-auxiliary env's
   production jar (`remap<Env>Jar`, or the env's plain `jar`), keyed
   by `loaderSpec.name` (env id as fallback; same-loader collisions
   become `<name>-<envId>`). Needs no configuration.
2. **`forgix { }` DSL — optional.** When `io.github.pacifistmc.forgix`
   is also applied, entries in `forgix.mergeConfigurations` feed in
   (explicit `inputJar` wins; the subproject heuristic is the
   fallback). On flat projects, write them via the map — upstream's
   `merge(name)` validates before running its action and always
   throws for non-subproject names.
3. **`loaderMerge.from(...)` — optional override.** Only needed for
   extra inputs or key collisions — the env feed already covers the
   standard case:

```kotlin
uniminedLoaderMerge {
    from(
        "quilt" to tasks.named("remapQuiltJar").flatMap { it.archiveFile },
    )
}
```

Inputs may be `File`s, task references, or providers — task-output
providers carry their producer, so inputs always build before the
merge runs.

## Consumers

`adapter-modpublisher` prefers the `mergedElements` artifact as the
upload artifact automatically — and suppresses the per-loader jars —
so applying all three plugins means a merged multi-loader jar is what
gets published.
