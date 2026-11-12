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

`genModSources` / `gen<Env>ModSources` remaps the mod's own sources
jar through the same `SOURCES` surface so published `-sources`
classifiers carry the production namespace.
