# Installation

Unimined 2.0 is a Gradle plugin. In `settings.gradle.kts`, register the
maven repo and (for local composite development) optionally the
`includeBuild` path:

--8<-- "generated/settings.gradle.kts"

Then apply the plugin in `build.gradle.kts`:

```kotlin
plugins {
    id("xyz.wagyourtail.unimined") version "2.0.0-SNAPSHOT"
}
```

!!! tip "Skip the typing"
    `unimined-cli new mymod --group com.example --version 1.20.1 --loader fabric=0.16.0`
    emits this whole structure for you — see [unimined-cli](cli.md).
