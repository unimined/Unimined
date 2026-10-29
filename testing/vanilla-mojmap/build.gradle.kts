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
        mappings {
            // bare builtin channel: the dev artifact is mojmap-named and
            // `remapJar` remaps mojmap -> official for production
            mojmap()
        }
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
