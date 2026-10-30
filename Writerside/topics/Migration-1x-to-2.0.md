# Migrating from 1.x to 2.0

Unimined 2.0 splits the monolithic `unimined { }` extension into
per-domain plugins with their own extensions. Groovy scripts that
call removed 1.x verbs now get a migration note naming the new
form; Kotlin DSL scripts fail at compile time — this table maps
old calls to their 2.0 equivalents.

## Environment configuration

| 1.x | 2.0 |
|---|---|
| `unimined.minecraft(sourceSet) { }` | `unimined.minecraft { }` + per-source-set env (`minecraft { sourceSet("main") { } }`) |
| `unimined.reIndev(sourceSet) { }` | env-level infra config inside `unimined.minecraft { }` |
| `unimined.bta(sourceSet) { }` | same — env-level inside `unimined.minecraft { }` |

Loader declaration moves onto the environment:
`loader("fabric", "0.16.0")` / `loader("fg1", "0.1.5")` /
`env.jarmod("x")` / `env.fg1("x")` / `env.customSearge()`.

## Remap pipeline

| 1.x | 2.0 |
|---|---|
| remap hooks inside `unimined { }` | `unimined.remap { }` — step verbs (`jst {}`, `unpick(...)`, `applyClassTweaker {}`, `sourceRemap {}`) per surface, both Groovy and Kotlin |
| `unimined.migrateMappings(sourceSet) { }` | `unimined.migrateMappings("main") { }` (env id), or `unimined.migrate { }` |

## Repositories

The ~20 `unimined.*Maven()`/`unimined.*Repos()` helpers
(`fabricMaven`, `minecraftForgeMaven`, `curseMaven`, `jitpack`,
`spigot`, `quiltMaven`, `ornitheMaven`, `legacyFabricMaven`,
`neoForgedMaven`, `cleanroomRepos`, `modrinthMaven`, …) are gone:
loader repositories are **auto-registered per environment** in
2.0. Remove the calls; use a normal `repositories {}` block for
anything custom. `modsRemapRepo` is likewise managed internally.

## Other moves

| 1.x | 2.0 |
|---|---|
| mod/transform config inside `unimined { }` | `unimined.mod { }`, `unimined.transform { }` |
| global toolchain toggle | `unimined.useToolchains = false` |
| env java override | `unimined.javaVersion` (delegates to the `main` env) |

Unknown or removed verbs raise a `GradleException` naming the
replacement — read the message; it points here.
