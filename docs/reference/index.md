# DSL Index

The 2.0 DSL surface, by extension:

| Block | Where | Purpose |
|---|---|---|
| `unimined { }` | root | top-level plugin extension |
| `minecraft { }` | `unimined` | env bound to a source set (native product) |
| `bta`/`reindev`/`nsss`/`custom(n)` | `unimined` | product envs |
| `loader(n, v)` | env | apply a modloader |
| `mappings { }` | env | remap chain |
| `mod { }` | `unimined` | mod metadata (id/depends/loader()/mixins) |
| `runs { }` | root | run configurations |
| `remap { }` | `unimined` | remap pipeline steps |
| `transform { }` | `unimined` | transform hooks |
| `migrate { }` | `unimined` | migration helpers |
| `loaderApi(n, v)` | `unimined` | loader api coordinates |
| `loaderApiModule(n, m, v)` | `unimined` | one api sub-module |
| `modImplementation` & co | `dependencies` | remapped mod deps (env-scoped) |

Member-level detail lives in the [API docs](api.md) (Dokka).

## Loader descriptor capabilities

Loader descriptors advertise static capabilities through a key→value
map; wiring consumes them when envs use the corresponding features.
Power users can read them as the descriptor contract:

| Capability | Consumed by | Value shape |
|---|---|---|
| `datagenMain` | `env.datagen()` → the `run<Data>` config | main class |
| `datagenArgs` | `env.datagen()` → program args | space-separated args; `{output}`/`{modid}` substituted |
| `datagenClientArg` | `datagen(client = true)` | appended flag (e.g. `--client`) |
| `gametestJunit` | `env.gametest()` → `testImplementation` | dependency coord; `{loader}` = loader version |
| `gametestClientJvmArgs` | `env.gametest()` → `<env>GametestClient` run | space-separated `-D` flags; `{modid}` substituted |
| `sidedArtifacts` | loader dep resolution | per-side artifact expansion (pre-2.9 foxloader, era splits) |
| `jvmTarget`/`javaVersion` | run + compile wiring | `(mc, loader) -> Int?` — the runtime bytecode floor |
| `modsDir` | `PrepareRunTask` staging | run `mods/` dir capability — dev jars stage in |
| `mainClass`/`tweakClass` | run wiring | loader launch entrypoint |
| `replaceLwjgl`/`replaceLwjglMatch` | native-era envs | LWJGL replacement coord + match rule |
| `libraryOverlayMeta` | Ornithe envs | version-manifest endpoint for lib overlays |
| `atTool` | forge envs | access-transformer tool coord |
| `supportedProducts` | env binding | `"*"`/exact/`"scope:"` product whitelist |
