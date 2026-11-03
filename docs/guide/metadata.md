# Mod Metadata

`mod { }` inside `unimined { }` is the mod identity + runtime
metadata, emitted into the loader's descriptor (fabric.mod.json,
mods.toml, …) at build time:

--8<-- "generated/mod-metadata.gradle.kts"

- `id`, `name`, `version`, `description`, `authors`, `license`, `url`, `icon`
- `depend(modId, versionRange)` — metadata deps
- `loader("fabric") { }` / `fabric { }` — loader-specific sections
  (entrypoints, extra keys)
- `mixins { create("name") { } }` — generated `*.mixins.json` configs

`--metadata dsl` on the CLI emits this block instead of hardcoding
values in the loader descriptor.
