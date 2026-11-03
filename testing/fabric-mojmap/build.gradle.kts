plugins {
    java
    id("xyz.wagyourtail.unimined")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

unimined {
    minecraft {
        version = "1.20.1"
        loader("fabric", "0.16.0")
        mappings {
            // mojmap dev namespace on fabric: the chain composes
            // official -> intermediary (loader hop) + official -> mojmap,
            // so prod remap is mojmap -> intermediary
            mojmap()
        }
    }
    mod {
        id = "testmod"
        name = "Test Mod"
        depend("fabricloader", ">=0.16.0")
        depend("minecraft", "~1.20.1")
        mixins {
            create("testmod") {
                packageName = "com.example.mixin"
                mixin("ExampleMixin")
            }
        }
        loader("fabric") {
            entrypoint("main", "com.example.TestMod")
            environment("*")
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
