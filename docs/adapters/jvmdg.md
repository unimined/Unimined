# JvmDowngrader adapter

Plugin id: `xyz.wagyourtail.unimined.jvmdg`

Bridges [JvmDowngrader](https://github.com/unimined/JvmDowngrader)
into Unimined so environments targeting ancient JVMs (Java 8 and
below-era loaders) can compile on a modern JDK while producing
downgraded bytecode for dev runs, jars, and api jars.

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
            // downgrading is already on — only write jvmdowngrader {}
            // to change the inferred target or opt this env out
            jvmdowngrader(8)
            // or jvmdowngrader { enabled.set(false) }
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
            // downgrading is already on — only write jvmdowngrader {}
            // to change the inferred target or opt this env out
            jvmdowngrader { target.set(8) }
            // or jvmdowngrader { enabled.set(false) }
        }
    }
    ```

Downgrading is **on by default for every environment** — applying
the plugin is the opt-in. `target` conventions to each env's own
Java floor (the version-json `javaVersion`, floored at 7 for
≤1.6.4-era), so most projects need no per-env config at all.

Envs whose compile output is already at/below the target pass
through untouched. When enabled it:

- supplies a `downgrade` step on the JAR surface — the shipped jar
  carries downgraded bytecode
- produces a downgraded api jar for downstream consumers
- makes dev `run*` tasks launch against downgraded copies of your
  compile outputs — old forge runtimes (ASM 4 / patched ASM 5.2)
  can't read modern class files, so this is required for them to
  run at all

## Requirements

- Java 17+ to *run the build* (the adapter itself targets 17)
- Consumes `xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:2.0.1`
  directly — **do not also apply JvmDowngrader's own Gradle plugin**,
- Old-forge dev runs *require* this when your compile target is
  newer than Java 8
