# Environments

An *environment* is a Minecraft instance bound to a source set — the
unit that owns a product, loaders, mappings, run configs, and `mod*`
dependency configurations.

```kotlin
unimined {
    minecraft {
        // bound to main by default
        version = "1.20.1"
        loader("fabric", "0.16.0")
    }
    minecraft {
        sourceSet("server") {
            loader("forge", "47.2.0")
            side = Side.SERVER
        }
    }
}
```

- `sourceSet(name)` binds the env to a source set (default `main`);
  each env is independent — no `combineWith`.
- `side` selects `Side.CLIENT`/`SERVER`/`MERGED`
  ([example](../generated/side-server.gradle.kts)).
- Multiple envs in one project = flat multi-env;
  [multi-module](../guide/multi-project.md) is the alternative.

Envs are also the scope of `mod { }` metadata and `runs { }`.
