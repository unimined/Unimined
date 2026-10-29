plugins {
    java
    id("xyz.wagyourtail.unimined")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

repositories {
    mavenCentral()
}

unimined {
    minecraft {
        version = "1.20.1"
        loader("forge", "47.3.0")
        mappings {
            mojmap()
        }
    }
}

tasks.register("dbg") {
    doLast {
        val env =
            project.extensions.getByType(xyz.wagyourtail.unimined.minecraft.MinecraftExtension::class.java).environments.getByName(
                "main"
            )
        println("[dbg] loaderSpec=${env.loaderSpec.orNull} provider=${env.provider.orNull}")
    }
}

tasks.register("dbg2") {
    doLast {
        println("[dbg2] builtins=" + xyz.wagyourtail.unimined.transform.loader.LoaderRegistry.builtins().map { it.id })
    }
}

runs {
    named("server") {
        // `-PautoStop` — queued on stdin, executes on the first tick
        // after "Done"; boot-validation runs exit on their own
        if (providers.gradleProperty("autoStop").isPresent) {
            stdin.set("stop\n")
        }
    }
}
