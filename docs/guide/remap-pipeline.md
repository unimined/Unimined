# Remap Pipeline

`unimined { remap { } }` configures the remapping steps — the verbs are
ordered pipeline steps applied to the MC jar and remapped mod deps:

```kotlin
unimined {
    remap {
        unpick("path/to.unpick")
        applyClassTweaker { /* custom bytecode transform */ }
        sourceRemap { /* UML source remapper — the default engine */ }
        jst { /* explicit opt-in: Java Source Transform */ }
    }
}
```

Source remapping runs through UML's source remapper by default;
`jst { }` remains as an explicit engine opt-in for verification or
alternate-engine use.

## Mod artifacts

After the namespace `remap` step, JAR-surface pipelines automatically
run a `modArtifacts` step that rewrites mod-jar metadata the base
remap leaves untouched:

- `kotlin.Metadata` internal names (any Kotlin mod jar)
- mixin configs and refmap emission for jars carrying `*.mixins.json`
- JarModAgent/ClassTransform refmaps for jars whose manifest declares
  `JarModAgent-Refmaps`

The step is engine-agnostic — it applies identically when `remap` is
overridden with `remap("tiny")`, and to remapped dependency views, so
mod deps get mixin refmaps for the dev runtime. Declare it explicitly
with `jar { modArtifacts() }` only to pin an engine or ordering; it is
skipped on identity remaps and on jars without the relevant resources.

## Standalone artifact remapping

`unimined:remap` works outside a Minecraft environment — any jar can be
remapped between namespaces with `remap.artifact(...)`, which registers
a `remap<Name>Artifact` task:

```kotlin
remap {
    artifact("baritone") {
        input.set(layout.projectDirectory.file("baritone.jar"))
        mappings.set(layout.projectDirectory.file("mapping.txt"))
        from.set("target")   // optional — see autodetection below
        to.set("source")
        // output defaults to build/remapped/baritone-remapped.jar
    }
}
// ./gradlew remapBaritoneArtifact
```

The mapping file is autodetected from its contents at execution time —
no format flag is needed:

| Format | Notes |
|---|---|
| SerializedMappingData bundle | Native unimined zip; richest payload (multiple views) |
| MappingBundle JSON | Legacy unimined row bundle |
| tiny v1 / v2 | Fabric-era standard |
| UMF | Unimined Mapping Library universal format |
| SRG / CSRG / package SRG | Forge-era formats |
| TSRG v1 / v2 | Forge-era formats |
| ProGuard / RetroGuard | Common for shipped obfuscated jars (e.g. Baritone) |
| MCP / mcpconfig | MCP exports — v1/v3/v6 method+field CSVs, mcpconfig zips |
| Parchment | Javadoc/param overlay — must be named `parchment.json` to autodetect |

ProGuard maps are the common case for third-party obfuscated jars
(e.g. remapping a shipped mod to readable names). Sidecar formats
(AW/AT/CT, nest, exception, signature, unpick) also parse via UML but
carry no class-name columns, so they are not useful as a standalone
`mappings` input.

When `from`/`to` are unset, namespaces are autodetected from the input
jar: the namespace column whose class names best match the jar's
contents becomes `from`, and the single remaining column becomes `to`.
Autodetection fails with an actionable error when no column matches or
more than one target candidate remains — set both explicitly in that
case.

The step pipeline is shared with the environment machinery — `remap {
jar { } }` declarations apply to artifact tasks too (engine selection
via `remap("tiny")`, access steps, custom steps). Environment-scoped
steps and `modArtifacts` are not applied; those only make sense on
environment jars.

## Production-jar validation (`footgunChecks`)

After the pipeline, `remap<JarTask>` (and `remap<Name>Artifact`) stamps
the manifest with `Unimined-Mapping-Namespace: <target>` — a remapped
jar is self-describing — then lints the emitted artifact for packaging
mistakes that compile fine but fail at runtime:

- metadata files parse (`fabric.mod.json`, `quilt.mod.json`,
  `mcmod.info`, `META-INF/mods.toml`, `META-INF/neoforge.mods.toml`,
  `plugin.yml`/`bungee.yml`/`paper-plugin.yml`, `*.mixins.json`) and
  carry their required keys
- every path the metadata references exists inside the jar:
  `accessWidener` files, declared mixin configs, `jars[]` nested jars,
  `icon` files
- classes named by metadata exist: fabric `entrypoints`, mixin classes
  listed in `*.mixins.json`, the `plugin.yml` `main` class
- the manifest namespace marker is present and matches the target
  namespace

Findings default to `ERROR` — the task fails. The level is a
`remap`-block property:

```kotlin
unimined {
    remap {
        footgunChecks.set(FootgunCheckLevel.WARN) // log and continue; OFF disables
    }
}
```

Sources jars get the marker but skip metadata/class checks (a sources
jar legitimately lacks `.class` files). Only unimined-produced
artifacts are linted — third-party jars in transforms are never
validated.
