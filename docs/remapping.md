# Remapping

Mods you depend on are remapped onto your environment's mappings.
Each `minecraft { }` env exposes `mod*` configurations scoped to its
source set — `modImplementation`, `modCompileOnly`, `modLocalRuntime`:

```kotlin
dependencies {
    modImplementation("maven:coord")
    // loader api helpers resolve the coordinate for you:
    // modImplementation(unimined.loaderApi("fabric", "0.92.2+1.20.1"))
}
```

The remap pipeline itself is configured in `unimined { remap { } }` —
step verbs like `unpick(...)`, `applyClassTweaker { }`, and
`sourceRemap { }` (UML source remapper by default; `jst { }` is the
explicit opt-in).
