# Run configurations

`runs { }` is a top-level block in 2.0 (it moved out of
`unimined { }`). Each registered run gets a Gradle task + an IDE run
config:

```kotlin
runs {
    create("client") {
        mainClass = "net.minecraft.client.main.Main"
        jvmArgs += "-Xmx2G"
        programArgs += "--accessToken=0"
    }
    create("server") {
        mainClass = "net.minecraft.server.Main"
    }
}
```

Envs produce run tasks automatically from their loader's defaults;
`create(name) { }` adds custom ones. `autoStop` and other boot-time
options live on the run config.
# Auth

Run configs authenticate through
[gradle properties/env vars](https://docs.gradle.org/current/userguide/build_environment.html#sec:gradle_configuration_properties)
— put them in the
[global gradle.properties](https://docs.gradle.org/current/userguide/directory_layout.html#dir:gradle_user_home)
so they never land in the repo:

```properties
unimined.auth.username=you@example.com
unimined.auth.password=...
```

The run configs pick them up — `runs { }` is top-level in 2.0:

```kotlin
runs {
    create("client") { /* auth flows from the properties above */ }
}
```
