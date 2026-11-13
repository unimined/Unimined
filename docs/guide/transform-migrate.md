# Transform & Migrate

## Transform

`unimined { transform { } }` is the environment-transform surface the
`transform-*` modules plug into — each loader module (forge, fabric,
jarmod, bukkit, …) registers its pipeline here, and you can hang
project-local steps on the same hooks.

```kotlin
unimined {
    transform {
        // every environment
        transformer(MyTransformer())

        // one environment's source-set spec
        sourceSet("main") {
            transformer(EnvOnlyTransformer())
            decorator(LogOutputDecorator())
        }

        // run-time transform behaviour for a specific run config
        transformRun("client") { /* ... */ }
    }
}
```

Per environment (`sourceSet("<env>") { }`):

| Knob | Purpose |
|---|---|
| `transformer(t)` | append a `MinecraftTransformer` to the env's jar pipeline |
| `decorator(d)` | append a `TransformDecorator` (log/observe the pipeline) |
| `nestedJars { }` | jar-in-jar packaging — see [Dependencies](dependencies.md#jar-in-jar-include) |
| `features { }` | feature flags the env's pipeline consults |

Transformer execution is lazy: each registered transformer becomes a
Gradle task in the env's chain (`prod-<jarTask>` → `remap<JarTask>`),
with inputs wired through providers so the pipeline stays
configuration-cache safe.

Most projects never touch this — loaders wire their own transforms
(patch apply, userdev pipeline, jar merge) and mod packaging lives in
[Mod Metadata](metadata.md) + `remap { }` steps
([Remap Pipeline](remap-pipeline.md)).

## Migrate

`unimined { migrate { } }` registers `migrateMappings` — the loom
`migrateMappings` equivalent: rewrite your sources onto a different
mappings release (or a different mapping family) in one pass.

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        migrate {
            migrateMappings("main") {
                target {
                    yarn("1.20.2", 2)      // any mappings {} verb works here
                }
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        migrate {
            migrateMappings("main") {
                target {
                    yarn("1.20.2", 2)
                }
            }
        }
    }
    ```

Then:

```bash
./gradlew migrateMappings          # main env
./gradlew migrateMappingsFabric    # a non-main env
```

Migrated sources land in `remappedSrc/` — outside `src/` so they
can't become an accidental source root. Non-code files copy verbatim;
rewrites are reported per file and unmapped references come out as
warnings, not failures.

### How it works

The task **grafts** your environment's resolved mapping tree onto the
target tree through a shared namespace, producing a direct
`devNamespace -> target` mapping, then runs a source remapper over
`allSource`. The bridge namespace is picked in order:

1. `bridgeNamespace.set("...")` if you set it
2. the env's production (`modSourceNamespaces`) namespaces
3. `intermediary`, then `official`
4. any other namespace the two trees share

If the trees share nothing it fails listing the candidates — pick
compatible mappings or set `bridgeNamespace` explicitly. The target
column is auto-detected (`named`/`mojmap`/`yarn`/`mcp`, else last);
override with `targetDevNamespace` when the target carries several.

### Task knobs

| Knob | Purpose |
|---|---|
| `target { }` | target mappings — the full `mappings { }` DSL (`yarn(...)`, `mcp(...)`, `maven(...)`, `builtin(...)`, `file(...)`, …) |
| `classpath("g:a:v")` | extra artifacts on the remapper classpath — e.g. the *new-version* intermediary jar for cross-MC-version migrations |
| `bridgeNamespace` | explicit graft bridge when auto-detection can't find a shared namespace |
| `targetDevNamespace` | which target column becomes the new sources' namespace |
| `outputDir` | default `remappedSrc/` |

Typical flows: yarn→newer-yarn across MC versions (bridge:
`intermediary`, add `classpath("net.fabricmc:intermediary:<newMc>")`),
dev-named → mojmap (bridge: `official`), and MCP/SRG-era upgrades
(bridge: `searge`).

The result is a *source* rewrite — after diffing `remappedSrc/` into
`src/`, update `mappings { }` to match the target you migrated to.
