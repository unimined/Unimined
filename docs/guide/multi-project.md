# Multiple Source Sets & Projects

> 2.0 rewrite. The 1.x `unimined.minecraft(sourceSets.x)` /
> `combineWith(...)` APIs are gone — see
> [Migrating from 1.x](migration-1x-to-2.0.md).

Two shapes cover the 1.x use cases:

- **flat** — several `minecraft { }` envs in one project, each bound
  to a source set;
- **multi-module** — a `common` module + one subproject per loader
  (what `unimined-cli new --modules` generates).

## Flat: one env per source set

Each `minecraft { sourceSet(...) { } }` block is an independent
environment — its own product, loader, mappings, runs. Envs with no
`sourceSet` call bind to `main`.

```kotlin
unimined {
    minecraft {
        sourceSet("main")
        loader("fabric", "0.16.0")
    }

    minecraft {
        sourceSet("server")
        loader("forge", "47.2.0")
        side = Side.SERVER
    }
}
```

Run configurations come from each env's `run { }`; there is no
`combineWith` — an env that should share another's classpath just
points at the same source set or module dependency.

## Multi-module: common + per-loader subprojects

This is what `unimined new mymod --modules --loader fabric=…
--loader forge=…` emits. Settings includes every module:

--8<-- "generated/multiloader-settings.gradle.kts"

A loader module applies unimined, declares its env on `main`, and
depends on `:common` for shared code:

--8<-- "generated/multiloader-fabric.gradle.kts"

`common` gets a loader-less env (MC classpath + mappings only); the
loader jars land as `modImplementation` deps in each loader module.
