# Multiple Source Sets & Projects

> 2.0 rewrite. The 1.x `unimined.minecraft(sourceSets.x)` API moved —
> envs live in `minecraft { sourceSet(...) { } }`; see
> [Migrating from 1.x](../migration-1x-to-2.0.md).

Two shapes cover the 1.x use cases:

- **flat** — several `sourceSet` envs in one project;
- **multi-module** — a `common` module + one subproject per loader
  (what `unimined-cli new --modules` generates).

## Flat: one env per source set

Each `minecraft { sourceSet(...) { } }` block is an independent
environment — its own product, loader, mappings, runs:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        minecraft {
            fabric("0.16.0")            // env "main"

            sourceSet("forge") {
                version = "1.20.1"
                forge("47.2.0")
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        minecraft {
            fabric("0.16.0")            // env "main"

            sourceSet("forge") {
                version.set("1.20.1")
                forge("47.2.0")
            }
        }
    }
    ```

### Sharing between envs

Envs are independent by default; three mechanisms share
configuration and classpath:

- **Implicit parent** — an env declared *inside* another env's
  block (`sourceSet("datagen") { datagen() }` style nesting, and
  the `datagen`/`gametest` aux verbs) inherits the parent's
  sections automatically. Opt out with `inheritParent.set(false)`.
- **`combineWith(...)`** — pull another env's declarations in
  explicitly: `combineWith("main")`, `combineWith("common", env)`
  with `sections` to take only some (mappings, runs, deps, …).
- **Module deps** — `modImplementation(project(":common"))`-style
  references share code across projects.

Sibling `sourceSet` envs at `minecraft {}` top level don't share —
`combineWith("main")` is the way to layer the main env's config in.

### Merged: several loaders on one environment

Repeat loader declarations inside a single `sourceSet` env to
compile that source set against several loaders' APIs at once — the
2.0 analog of 1.x's `merged { fabric; forge }`. The **first**
declaration is the environment's primary: it owns the production
namespace, remapping, metadata emission, and run configs. Every
further declaration adds a *secondary* whose product and
libraries merge onto the env's **compile** classpath only — this is
not a client/server merge (`Side.MERGED` is the other axis) and not
a packaging merge:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        minecraft {
            sourceSet("common") {
                version = "1.20.1"
                fabric("0.16.0")   // primary — prod/remap/runs
                forge("47.2.0")    // secondary — compile only
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        minecraft {
            sourceSet("common") {
                version.set("1.20.1")
                fabric("0.16.0")   // primary — prod/remap/runs
                forge("47.2.0")    // secondary — compile only
            }
        }
    }
    ```

Named verbs and `loader(name, version)` mix freely — declaration
order is all that matters.

The env's effective `javaVersion` floor takes the strictest across
all declared loaders. Secondary loaders land on dedicated
configurations (`mergedLoader<Env>` for most loaders, the
family-probed `forgeMergedLoader<Env>` plus
`fabricMergedLoaderLibraries<Env>` for loader-embedded libs) — all
compile-only, so `common` sources see every declared loader's API
while production stays per-loader through the normal artifact
ladder.

Per-loader envs that need their own runs or outputs compose from the
merged env: `combineWith("common")` (or
`copyFrom("common", LOADER)`) validates by *membership* — an env may
combine from a merged source when its own loader is among the
source's declared loaders. Run configs themselves stay per-loader —
the merged env's runs launch under the primary; to launch under a
secondary, give that loader its own env combining the merged one.

## Multi-module: common + per-loader subprojects

This is what `unimined new mymod --modules --loader fabric=…
--loader forge=…` emits. Settings includes every module:

=== "Kotlin `settings.gradle.kts`"
    ```kotlin
    --8<-- "generated/multiloader-settings.gradle.kts"
    ```

=== "Groovy `settings.gradle`"
    ```groovy
    --8<-- "generated/multiloader-settings.gradle"
    ```
`common` gets a loader-less env (MC classpath + mappings only):

=== "Kotlin `common/build.gradle.kts`"
    ```kotlin
    --8<-- "generated/multiloader-common.gradle.kts"
    ```

=== "Groovy `common/build.gradle`"
    ```groovy
    --8<-- "generated/multiloader-common.gradle"
    ```

A loader module applies unimined, declares its env on `main`, and
depends on `:common` for shared code — the fabric/forge scripts are
the same shape with a different `loader()`.

**`fabric/build.gradle.kts`**:

=== "Kotlin `fabric/build.gradle.kts`"
    ```kotlin
    --8<-- "generated/multiloader-fabric.gradle.kts"
    ```

=== "Groovy `fabric/build.gradle`"
    ```groovy
    --8<-- "generated/multiloader-fabric.gradle"
    ```

**`forge/build.gradle.kts`**:

=== "Kotlin `forge/build.gradle.kts`"
    ```kotlin
    --8<-- "generated/multiloader-forge.gradle.kts"
    ```

=== "Groovy `forge/build.gradle`"
    ```groovy
    --8<-- "generated/multiloader-forge.gradle"
    ```
The loader jars land as `modImplementation` deps in each loader module.
Cross-project inheritance uses the `<project>:<env>` path form —
`combineWith(":common:main")` pulls the `common` project's `main`
env (classpath included, so shared code resolves); `copyFrom`
reuses sections without composing output.

### Access declarations in `common`

A loader-less env applies access files to its own dev artifact the
same as a loader env — the apply step is contributed by the shared
wiring rather than a loader family, so `access { }`, `from(...)`
files, and AT/AW/CT files under `common/src/main/resources` all
take effect where `common` compiles. Downstream envs get the same
rules through `combineWith`: `access { }` rules re-emit in each
child loader's consumed format (AW/CT for fabric, AT for forge),
while declared/discovered files propagate through the file
collections and merge into each child's canonical doc. Per-file
options (`from(path) { output }`), `fileName`, and `format` follow
the same propagation — a child's own `access { }` block wins when
it redeclares a name or format.

Dependency-declared access applies to a `common` env too (loom
`transitiveAccessWideners` parity): `modImplementation`/
`modRuntimeOnly` jars are scanned for conventional access docs —
`*.accesswidener`/`.classtweaker` entries, `fabric.mod.json`
`accessWidener`/`classTweakers` refs, `META-INF/accesstransformer.cfg`
+ `FMLAT` manifest entries, and `custom.*injected_interfaces` keys
(synthesized as `transitive-inject-interface` rules). AW/CT docs
apply their `transitive-*` rules only; ATs apply in full (FML
semantics). Headerless dep docs need an unambiguous
`modSourceNamespaces` entry to interpret — skipped with a warning
otherwise. Dep jars' embedded access docs are also rebased during
their prod→dev remap (`remap-access` step), matching the loader
envs.

A `common` env's access declarations also ship **inside its jar**
— the `architectury.common.json` equivalent. The canonical doc
emits as a self-describing `classTweaker` file at
`<env>-access.classtweaker` (`access { format = "at" }` switches
to `META-INF/<env>-access_at.cfg`, `"aw"` to `.accesswidener`,
and `access { fileName }` overrides the whole path), and
`from(path) { output }`/declared files land at their output or a
conventional path. Dependents that consume `common` as a plain
dependency — no `combineWith` — pick these up through the same
dep scan: **`transitive-*` rules cross to dependents**, the rest
stay env-local (loom `transitiveAccessWideners` semantics). The
production remap rebases the embedded doc alongside the jar's
other contents, so the published artifact declares it in the
production namespace.
