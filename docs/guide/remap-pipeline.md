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
