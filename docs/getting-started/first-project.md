# First Project

A flat, single-loader mod environment on native Minecraft:

--8<-- "generated/flat-fabric.gradle.kts"

Key pieces:

- `unimined { minecraft { } }` — an *environment* bound to the `main`
  source set. `version` is the MC version; `loader("fabric", "…")`
  applies a loader.
- `mappings { }` — the remap chain (`yarn(10)` here; see
  [Mappings](../guide/mappings.md)).
- `mod { }` — mod metadata (`id`, `name`, `version`, `depends`) emitted
  into the loader's descriptor at build time.
- `dependencies { }` — env `mod*` configurations take mod artifacts and
  remap them onto your mappings.

Next: more [environments](../guide/environments.md), other
[products](../guide/products.md), or a
[multi-module layout](../guide/multi-project.md).
