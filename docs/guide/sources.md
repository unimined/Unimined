# Sources

`xyz.wagyourtail.unimined.source` wires `genSources` /
`gen<Env>Sources` per bound environment. The default pipeline for an
environment with no provider-supplied sources:

1. **Decompile** — the env's dev-namespace Minecraft artifact through
   the selected engine (`vineflower` by default). Inputs are the jar,
   classpath, and engine options, so up-to-date checks skip reruns
   (`@DisableCachingByDefault` — no build-cache entry).
2. **Sources-surface steps** — the env's `RemapSurface.SOURCES`
   pipeline (e.g. `uml:remap` renaming the decompiled `.java` to the
   dev namespace) runs over the decompiled jar when steps are
   declared.
3. **Linemapped classifier** — `gen<Env>Linemapped` applies the
   vineflower `0x4646` linemap extras to the binary jar's
   `LineNumberTable`s, producing `-linemapped.jar` whose stack traces
   and debugger line mappings track the decompiled/enriched source
   lines.

`bsm=1` (bytecode source mapping — the linemap extras) is on by
default; engine options can override it:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        source {
            engine.set("vineflower")
            options.put("bsm", "1")
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        source {
            engine.set("vineflower")
            options.put("bsm", "1")
        }
    }
    ```

Artifacts land in the env's generated local-repo as
`minecraft-<env>-<version>-sources.jar` and `-linemapped.jar`.

## External decompiler engine

`engine.set("external")` runs any fernflower-CLI-compatible decompiler
jar — selected by coordinate via `generator(dep)` (a bare version
string is shorthand for `org.vineflower:vineflower:<version>`):

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        source {
            generator("org.vineflower:vineflower:1.10.1")
            // or pin a different decompiler entirely:
            // generator("org.quiltmc:quiltflower:1.9.0")
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        source {
            generator("org.vineflower:vineflower:1.10.1")
        }
    }
    ```

## Fork options

`jvmArgs` (default `-Xmx4G -Xms1G -Xss4M`) sizes the forked decompiler
JVM; `javaVersion` (default `17`, empty string disables) selects the
toolchain it runs on; `args` (default `-jrt=1`) appends raw CLI
arguments after the `-key=value` engine options.

## Provider-supplied sources

When the product provider ships a `devSources` artifact (e.g. Forge
userdev's mcpconfig chain), the decompile step is skipped entirely —
the supplied sources remap straight to the dev namespace.

## Mod sources

Three distinct "mod sources" flows exist — they answer different
questions:

- **Your own sources for publishing** — the env's `remap<SourcesJar>`
  output rides the `*ProdSourcesElements` variant of the
  `unimined<Env>` component, so a published `-sources` classifier
  carries the production namespace (see
  [Publishing](publishing.md)).
- **Dependencies' published `-sources` jars** — the
  `<bucket>SourcesRemapped` views below remap each dep's shipped
  sources artifact into the dev namespace for IDE attach.
- **Dependencies without shipped sources** — `genModSources` /
  `gen<Env>ModSources` *decompiles* the env's `modImplementation` /
  `modCompileOnly` / `modRuntimeOnly` dependencies (already remapped to the dev
  namespace) through the same engine + `RemapSurface.SOURCES`
  pipeline as `genSources`, then publishes each result under the
  dep's own coordinates in the env's generated local repo. The dep
  set is resolution-time and unbounded, so the decompile runs as an
  artifact transform chained off the remapped mod views — the task
  just resolves the views and writes the repo entries.

## Dependency `-sources` attach

Every bound environment also gets per-bucket views over the mod
dependencies' *published* sources jars, complementing `genModSources`
for deps that ship `-sources` at all (no decompile, no repo
publish — the remapped file materializes at resolution like any
transform output):

- `<bucket>SourcesRemapped` requests each dep's docs variant
  (`category=documentation`, `docsType=sources` — maven's
  `-sources.jar` or a `withSourcesJar`-style `sourcesElements`
  variant) and runs it through the environment's
  `RemapSurface.SOURCES` pipeline, mod source namespace → dev
  namespace, so IDE-attached sources carry dev names.
- `<bucket>SourcesDirect` is the identity-environment pass-through
  twin; consumers pick by `isIdentityRemap` exactly like the
  `*Direct`/`*Remapped` jar views.

The active remapped views are registered on
`MinecraftExtension.modDevSourcesViewConfigurations` under
`"<environmentId>:<bucket>"` keys (the sources counterpart of
`modDevViewConfigurations`). Because the dep source namespace is
the declared one — `modSourceNamespaces`, like the jar views — a
sources jar published in another namespace remaps wrongly.

Consumers resolve leniently and filtered to module/project
component ids: deps that ship no `-sources` artifact contribute
nothing, and file deps must be excluded or they would contribute
their own jar as "sources".

## Decompiler cleanup on mod sources

Fernflower-era decompilers emit raw numeric arguments for OpenGL
calls — `GL11.glBegin(7)` instead of `GL11.glBegin(GL11.GL_QUADS)`.
Each bound environment gets an opt-in `cleanupSources` /
`cleanup<Env>Sources` task that rewrites those literals to the named
`GLxx`/`ARB*` constants using the same `gl_constants.json` dataset
RetroFuturaGradle ships (`applyDecompilerCleanupToMain` parity):

```bash
./gradlew cleanupSources        # main environment's source set
./gradlew cleanupServerSources  # the 'server' environment
```

The task walks the environment's bound source set's `java.srcDirs`
and **modifies matching `.java` files in place** — run it once after
pasting decompiled code into your mod, not as part of the normal
build. Only files importing `org.lwjgl.opengl.` are touched, only
changed files are written (atomically), and constant-owning classes
the rewrite references (e.g. `ARBVertexBufferObject`) get their
imports inserted automatically. Generated/patched source dirs the
environment injects under `build/` are excluded — the task is for
your checked-in sources.
