# Dependencies

## Mod dependencies (artifacts)

Each env exposes `mod*` configurations scoped to its source set —
mod jars land remapped onto your mappings:

```kotlin
dependencies {
    modImplementation("maven:coord")
    modCompileOnly("maven:coord")
    modLocalRuntime("maven:coord")
}
```

## Loader apis

`unimined.loaderApi(name, version)` resolves a loader's api artifact
bundle; `loaderApiModule(name, module, version)` resolves one
sub-module. Both also record the api modid into `mod { depends }`.

--8<-- "generated/tables/api-providers.md"

## Metadata-only dependencies

`mod { depend("modid", "versionRange") }` declares runtime deps in the
emitted mod metadata without touching the classpath.
