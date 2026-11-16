# Fork Development with Patch Sets

Fork-dev is the mache/git-patch workflow for projects whose real
content is a set of edits *on* Minecraft (or another mod's) decompiled
sources — a patchbase modloader, a hacked MC, a heavily patched
dependency. The git repo tracks only unified diffs; everything else
decompiles and materializes on build.

The workflow lives in the
[`adapter-patchbase`](../adapters/patchbase.md) plugin —
`xyz.wagyourtail.unimined.adapter-patchbase` — which bridges the
standalone [PatchBase](https://github.com/unimined/PatchBase) patch
tooling onto Unimined environments. It composes two loops:

- **Text patch loop** (this page) — `patches/` unified diffs over the
  environment's decompiled sources. This is the mache model: the patch
  dir is the source of truth, the patched source tree is generated.
- **Class patch loop** — the same edits, diffed at the *binary* level
  into a shippable `.binpatch`/`.bdiff` patch jar that other projects
  consume via `patchBase("g:a:v")` — Forge-binpatch-style mutation of
  the env jar, pre-remap.

## Layout

```text
my-fork/
├── build.gradle.kts
├── patches/            # git-tracked: *.java.patch diffs + verbatim
│                       #   *.java for net-new files
└── src/main/java/      # GENERATED patched overlay — gitignore it
```

```gitignore
# the patch overlay is generated material, not hand-written source
/src/*/java/
```

## The loop

```kotlin
unimined {
    minecraft {
        sourceSet("main") {
            version.set("1.7.10")
            patchBaseCreator()
        }
    }
}
```

1. **Materialize** — `gradlew applySourcePatch` runs `genSources`
   first (decompile via [unimined.source](sources.md) — declare the
   env, the task exists), then applies `patches/` onto the decompiled
   tree and writes **only patch-covered files** into
   `src/<env>/java`. `patches/` absent → the task skips; an empty set
   produces an empty overlay.
2. **Edit** — work in `src/<env>/java` like a normal source tree. The
   env compiles it as its own sources; patched classes win over the
   stock jar at dev runtime through JarModAgent's priority classpath.
3. **Commit** — `gradlew createSourcePatch` diffs `src/<env>/java`
   back against the decompiled base and rewrites `patches/` — new
   files copy verbatim, edits become `*.patch` unified diffs. This is
   what you commit.
4. **Ship** — `gradlew createClassPatch` diffs the env's production
   jar (`remap<Env>Jar`, else the env jar) against its pre-remap base
   and produces the publishable patch jar (classifier `patch`).
   Consumers apply it with `patchBase("g:a:v")`.

Non-`main` envs put the environment in the middle
(`applyClientSourcePatch`, `recompileClientMc`, …) and a per-env
patch dir (`patches/<env>`) + overlay (`src/<env>/java`). The old
suffix shape (`applySourcePatchClient`) still runs as a deprecated
alias.

## Recompiling a patched jar

`patchBaseCreator()` also wires the FG-era `recompMc` analog — for
when you need the *whole patched game* as a single artifact, not a
patch jar:

```text
recompileMcClasses   compiles the overlay's .java files against the
                     env's dev jar + Minecraft libraries
recompileMc          merges the compiled classes + verbatim resource
                     patches over the dev jar →
                     build/libs/minecraft-<env>-<version>-recompiled.jar
```

The merge is "first copy wins": the recompiled overlay replaces stock
class entries and overlay resources replace stock resources, while
unpatched classes, assets and data carry through from the dev jar
untouched. The stock manifest and signature blocks are stripped — a
jar whose contents changed can't keep Mojang's signatures.

`recompileMc` reads the overlay as it sits — run `applySourcePatch`
first to regenerate it from `patches/` (deliberately not chained:
the apply wipes the overlay, and auto-running it on every build
would drop uncommitted edits).

What it's for:

- a **local patched install**, or a fixture artifact for a
  downstream environment — `legacydist { file("…-recompiled.jar") }`
  with `namespace` set to the dev namespace the jar carries
- anything needing the whole patched MC in one file — binpatch
  bases, dev-bundle inputs, external tooling

What it isn't for:

- **dev runs** — the overlay already compiles into the env's own
  classes and wins on the priority classpath, so `runClient` sees
  your patches without a recompiled jar (and `makeStart` has no
  analog: run configs launch the dev artifact + libraries directly)
- **shipping to consumers** — `createClassPatch`'s binpatch jar is
  the distribution format (`patchBase("g:a:v")`)

!!! warning "The overlay is not a source dir"
    Both `applySourcePatch` and `createSourcePatch` delete and
    repopulate their output tree — treat `src/<env>/java` as the patch
    set's *checkout*, not a place for hand-written sources, or the
    next apply deletes them. Keep normal mod sources in a separate env
    or retarget the overlay via the spec below.

## Customizing the layout

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    sourceSet("main") {
        patchBaseCreator {
            patchDir.set(layout.projectDirectory.dir("mc-patches"))
            sourceDir.set(layout.projectDirectory.dir("fork-src"))
            diffContextSize.set(5)
            trimWhitespace.set(true)
            patchFormat.set("bdiff")
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    sourceSet("main") {
        patchBaseCreator {
            patchDir.set(layout.projectDirectory.dir("mc-patches"))
            sourceDir.set(layout.projectDirectory.dir("fork-src"))
            diffContextSize.set(5)
            trimWhitespace.set(true)
            patchFormat.set("bdiff")
        }
    }
    ```

| Option | Default | Meaning |
|---|---|---|
| `patchDir` | `patches` (`patches/<env>`) | git-tracked patch set — `*.patch` diffs + verbatim files |
| `sourceDir` | `src/<env>/java` | generated overlay — wiped on apply/create |
| `diffContextSize` | `3` | context lines in generated diffs |
| `trimWhitespace` | `true` | strip leading whitespace when diffing |
| `patchFormat` | `binpatch` | `createClassPatch` entry format — `binpatch` or `bdiff` |

See [adapters/patchbase](../adapters/patchbase.md) for the
consume-side DSL (`patchBase("g:a:v")`) and binding notes.
