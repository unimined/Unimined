# PatchBase adapter

Plugin id: `xyz.wagyourtail.unimined.adapter-patchbase`

Bridges [PatchBase](https://github.com/unimined/PatchBase) patch
sets into Unimined environments. Two flows:

- **Authoring** a patch set against Minecraft (a "patchbase
  modloader" project — the git repo carries only patch files)
- **Consuming** patch sets — modding against an MC jar mutated by
  `.binpatch`/`.bdiff` entries, the same seam Forge binpatches
  occupy

The adapter consumes the standalone `patchbase` artifact
(`xyz.wagyourtail.unimined:patchbase:2.0.0`) — PatchBase's core
has no Unimined dependency; this plugin is only the env bridge.

## Project layout (authoring)

```text
my-patchbase/
├── build.gradle.kts
├── patches/            # git-tracked unified diffs — the repo's
│                       #   only real content
└── src/main/java/      # generated patched sources — gitignore this
```

## build.gradle.kts (authoring)

```kotlin
plugins {
    id("xyz.wagyourtail.unimined") version "..."
    id("xyz.wagyourtail.unimined.adapter-patchbase") version "..."
}

unimined {
    minecraft {
        version = "1.7.10"
        patchBaseCreator()
    }
}
```

## Consuming patch sets

```kotlin
unimined {
    minecraft {
        version = "1.7.10"
        patchBase("com.example:my-patches:1.0")
    }
}
```

Resolves the dep onto the env's `patchBaseMain` configuration and
applies its class patches to the environment's MC jar before
remapping — compile classpath and dev runs both see the patched
game. Patch entries are `.binpatch` (standard record) or `.bdiff`
(PatchBase's checksum+gdiff unit); all other jar entries copy over
verbatim.

## Authoring a patch set

```kotlin
sourceSet("main") {
    patchBaseCreator()
}
```

Wires three tasks per environment:

| Task | Does |
|---|---|
| `applySourcePatch<Env>` | applies `patches/` unified diffs onto the `gen<Env>Sources` output, writing **only patch-covered files** to `src/<env>/java` (generated — gitignore it) so compile + JarModAgent see patched sources |
| `createSourcePatch<Env>` | diffs `src/<env>/java` back into `patches/` — keeps the git-tracked patch source in sync |
| `createClassPatch<Env>` | diffs the `remap<Env>Jar` output vs `minecraftRaw` into the shippable patch jar (classifier `patch[-env]`) |

Only `patches/` belongs in git. The generated patched sources and
the built patch jar are artifacts.

## Notes

- **Patch sets bind to a base jar.** `.binpatch`/`.bdiff` entries
  carry a checksum of the class they were built against — create
  patch jars against the env's *pre-remap* base (`minecraftRaw`),
  which is what `createClassPatch` uses, or consumers' checksums
  won't match
- The dev flow mirrors Forge binpatches: the patch jar mutates the
  MC jar, so the loader's classes exist for compile and runtime
  without loader sources or a recompile
