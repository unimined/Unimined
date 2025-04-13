# Run Config

unimined provides a runServer and runClient task for each sourceSet by default.
they extend the [`JavaExec`](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.JavaExec.html) gradle task type.

you can configure/disable these as follows:

<tabs group="lang">
<tab id="Groovy-Run-Config" title="Groovy" group-key="groovy">

```groovy

unimined.minecraft {
    ...
    runs {
        off = true // disable all run configurations
        config("client") {
           disabled = true // disable the runClient task
            
            javaVersion = JavaVersion.VERSION_1_8 // change the java version
            args += "--my-arg" // add an argument to the runClient task
        }
    }
}

```

for more details on what can be changed in the run configurations, see [RunConfig](https://unimined.wagyourtail.xyz/unimined/%version%/api-docs/unimined/xyz.wagyourtail.unimined.api.runs/-run-config/index.html)
