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
