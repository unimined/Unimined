# JvmDowngrader adapter

Plugin id: `xyz.wagyourtail.unimined.jvmdg`

Bridges [JvmDowngrader](https://github.com/unimined/JvmDowngrader)
into Unimined so environments targeting ancient JVMs (Java 8 and
below-era loaders) can compile on a modern JDK while producing
downgraded bytecode for dev runs, production jars, and api jars.

## Project layout

```text
my-mod/
├── build.gradle.kts
└── src/main/
```

## build.gradle.kts

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    plugins {
        id("xyz.wagyourtail.unimined") version "..."
        id("xyz.wagyourtail.unimined.jvmdg") version "..."
    }

    unimined {
        minecraft {
            version = "1.7.10"
            loader("forge", "10.13.4.1614")
            // global defaults — opts every env in:
            // compile at 21, emit each env's era floor
            jvmdowngrader {
                source.set(21)
            }
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    plugins {
        id 'xyz.wagyourtail.unimined' version '...'
        id 'xyz.wagyourtail.unimined.jvmdg' version '...'
    }

    unimined {
        minecraft {
            version.set("1.7.10")
            loader("forge", "10.13.4.1614")
            // global defaults — opts every env in:
            // compile at 21, emit each env's era floor
            jvmdowngrader {
                source.set(21)
            }
        }
    }
    ```

Applying the plugin is **not** the opt-in — it only adds a
`jvmdowngrader` extension to every environment **and** a global one
on `minecraft` itself, with `enabled` defaulting to `false`
everywhere. Envs opt in by configuring either spec:
`jvmdowngrader { }`, `jvmdowngrader(N)`, or `enabled.set(true)` — and
`enabled.set(false)` inside a block still wins, so the DSL can't
re-enable an env you meant to keep off.

`minecraft { jvmdowngrader { } }` is the **global** spec — its values
apply to every environment in the extension. Per-env specs on
`sourceSet("x") { jvmdowngrader { } }` override it, and for any flag
the precedence is: the env's own set, then its inherited envs
(nearest first), then the global spec, then the built-in default.
An env can opt back out under a global enable with
`enabled.set(false)`.

## `source` and `target`

Two separate levels, because downgrading decouples them:

| Setting | Meaning | Default |
|---|---|---|
| `source` | Java level `compile*` tasks run at — toolchain selection, `--release`, `sourceCompatibility`/`targetCompatibility`, Kotlin `jvmTarget` | the env's Java requirement (era floor or `javaVersion`) |
| `target` | class-file level JvmDowngrader emits, as a major (`8` → v52) | the env's Minecraft Java floor — the version-json `javaVersion`, floored at 7 for ≤1.6.4-era |

`source` raises the compile level **only** — run launchers and
tool executions still use the env's Java requirement, so a 1.7.10 env
with `source.set(21)` compiles on a Java 21 toolchain while FML still
launches on Java 8. Unset, the env compiles at its requirement and
the downgrade passes through untouched.

`target` is a convention, not a fixed value — most projects leave it
alone and get the era floor. To override:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    jvmdowngrader {
        source.set(21)
        target.set(11) // emit v55 instead of the era floor
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    jvmdowngrader {
        source.set(21)
        target.set(11)
    }
    ```

`jvmdowngrader(N)` remains as shorthand for `jvmdowngrader {
target.set(N) }`.

## Toolchains

With `minecraft.useToolchains` on (the default), `source` selects the
Java toolchain for `compile*` independently of the run JVM — e.g. a
Java 21 toolchain compiles while the run still launches on the era
JVM. With toolchains off, the build JVM itself must meet `source` —
a 1.7.10 env with `source.set(21)` requires running Gradle on Java
21+, and fails with a clear diagnostic otherwise.

## What gets downgraded

Once enabled and `source` exceeds `target`:

- **dev runs** — `run*` tasks launch against JvmDowngrader-processed
  copies of `classesDirs` plus downgraded api jars, so era loaders
  whose bundled ASM can't read modern class files (FG-era
  LaunchWrapper/FML reads ≤v52; ≤1.6.4-era reads ≤v51) run
  modern-compiled mods
- **production** — the same downgrade is contributed to the env's
  production jar pipeline, so `runProd` and published artifacts carry
  era-readable bytecode instead of raw `javac` output. It applies to
  the env's own jar only — the Minecraft artifact and remapped
  dependency views are untouched
- **api** — `jvmdowngrader-java-api` stubs are shaded into an
  `api-*.jar` next to the outputs so emitted classes resolve their
  `xyz.wagyourtail.jvmdg.jdk.stub.*` references at runtime

For ad-hoc jars outside the env pipeline, `remap { downgrade(8) }`
still applies the step directly to any remap surface.

## Engine flags

Both specs implement JvmDowngrader's Gradle `ShadeFlags`, so the
engine's own knobs are settable on either block — globally or per
env — and flow to both the dev-class and prod-jar downgrades:
`downgradeTo` (engine-level emit
level; a direct set wins over `target`), `apiJar`, `ignoreWarningsIn`,
`logLevel`, `multiReleaseVersions`, `shadeInlining`, `quiet`, and the
`debug*` switches. See JvmDowngrader's
[`DowngradeFlags`](https://github.com/unimined/JvmDowngrader) docs for
the full list.

## Upstream versions

Two pieces carry versions — they override independently.

**java-api stub jar** — bundled default
`xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:2.0.1`, resolved
per-environment from `uniminedTools.jvmdowngraderJavaApi`. Bump it to
pick up newer stub coverage (e.g. a newer Java major's
`VersionProvider`s) without a Unimined release:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    uniminedTools {
        jvmdowngraderJavaApi.set(
            "xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:2.1.0")
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    uniminedTools {
        jvmdowngraderJavaApi.set(
            'xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:2.1.0')
    }
    ```

A direct `jvmdgJavaApi<Env>` dependency also works and replaces the
default for that environment.

**Engine + global flags** — the engine stays on the adapter's
classpath at the bundled version, but applying JvmDowngrader's own
Gradle plugin (`xyz.wagyourtail.jvmdowngrader`) alongside this
adapter does two things: its `jvmdg { }` extension is detected and
becomes the deepest global flag layer (env set > inherited >
`minecraft { jvmdowngrader { } }` > `jvmdg { }` > built-in default),
and your `plugins {}` version wins classpath conflict resolution —
effectively bumping the engine the adapter links against:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    plugins {
        id("xyz.wagyourtail.jvmdowngrader") version "2.1.0"
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    plugins {
        id 'xyz.wagyourtail.jvmdowngrader' version '2.1.0'
    }
    ```

For an engine bump *without* applying upstream, use the classpath
substitution shown in [upstream versions](index.md#upstream-versions).

## Requirements

- Java 17+ to *run the build* (the adapter itself targets 17)
- Consumes `xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:2.0.1`
  directly (overridable via `uniminedTools.jvmdowngraderJavaApi`)
- Old-forge dev runs *require* this when your compile target is
  newer than Java 8
