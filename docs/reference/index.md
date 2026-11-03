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
