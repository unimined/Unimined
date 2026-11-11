# First Project

A flat, single-loader mod environment on native Minecraft:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    --8<-- "generated/flat-fabric.gradle.kts"
    ```

=== "Groovy `build.gradle`"
    ```groovy
    --8<-- "generated/flat-fabric.gradle"
    ```

=== "`gradle.properties`"
    ```properties
    --8<-- "generated/gradle.properties"
    ```
Key pieces:

- `unimined { minecraft { } }` — an *environment* bound to the `main`
  source set. `version` is the MC version; `loader("fabric", "…")`
  applies a loader.
- `mappings { }` — the remap chain (`yarn(...)` here — the version
  lives in `gradle.properties`; see
  [Mappings](../guide/mappings.md)).
- `mod { }` — mod metadata (`id`, `name`, `version`, `depends`) emitted
  into the loader's descriptor at build time.
- `dependencies { }` — env `mod*` configurations take mod artifacts and
  remap them onto your mappings.

Next: more [environments](../guide/environments.md), other
[products](../guide/products.md), or a
[multi-module layout](../guide/multi-project.md).
