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
