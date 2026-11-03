# Run Configs

`runs { }` is top-level in 2.0. Each run gets a Gradle task plus an IDE
run configuration:

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

Envs produce run tasks from their loader's defaults; `create(name) { }`
adds custom ones.

## Auth

Authenticate through
[gradle properties/env vars](https://docs.gradle.org/current/userguide/build_environment.html#sec:gradle_configuration_properties)
— put them in the
[global gradle.properties](https://docs.gradle.org/current/userguide/directory_layout.html#dir:gradle_user_home)
so they never land in the repo:

```properties
unimined.auth.username=you@example.com
unimined.auth.password=...
```
