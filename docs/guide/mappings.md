# Mappings

Each env declares a `mappings { }` chain — the sequence of mapping
namespaces the env's MC jar and mod deps get remapped through.

```kotlin
minecraft {
    mappings {
        yarn(10)              // fabric yarn
        // official()         // mojang official names
        // mcp("stable", "60") // MCP
        // searge()           // srg names
    }
}
```

Chain order matters — later entries layer onto earlier ones. The full
named-mapping catalog lives in the registry; generator-side, `--mappings`
takes the same DSL lines and `--var key=val` feeds mapping vars
(e.g. Ornithe's `ornitheGenVersion`).

Mod dependencies declared on env `mod*` configurations are remapped
through this chain automatically.
