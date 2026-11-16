# First Project

A flat, single-loader mod environment on native Minecraft — three
files: `settings.gradle.kts` (plugin + dependency repos — see
[Installation](installation.md) for what each block does),
`build.gradle.kts`, and `gradle.properties`.

**`settings.gradle.kts`** — plugin + dependency repos:

=== "Kotlin `settings.gradle.kts`"
    ```kotlin
    --8<-- "generated/settings.gradle.kts"
    ```

=== "Groovy `settings.gradle`"
    ```groovy
    --8<-- "generated/settings.gradle"
    ```

**`build.gradle.kts`** — plugin, env, mappings, deps:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    --8<-- "generated/flat-fabric.gradle.kts"
    ```

=== "Groovy `build.gradle`"
    ```groovy
    --8<-- "generated/flat-fabric.gradle"
    ```

**`gradle.properties`** — versions pinned per prop key:

=== "`gradle.properties`"
    ```properties
    --8<-- "generated/gradle.properties"
    ```
Key pieces:

- `unimined { minecraft { } }` — an *environment* bound to the `main`
  source set. `version` is the MC version; `fabric("…")` applies a
  loader (named verbs like `forge(…)`/`neoforge(…)` are sugar for the
  generic `loader("<id>", "…")` — see [Loaders](../guide/loaders.md)).
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
