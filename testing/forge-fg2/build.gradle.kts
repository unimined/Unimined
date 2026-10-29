plugins {
    java
    id("xyz.wagyourtail.unimined")
}

repositories {
    mavenCentral()
    maven("https://maven.minecraftforge.net/")
}

unimined {
    minecraft {
        version = "1.12.2"
        loader("forge", "14.23.5.2860")
        mappings {
            // FG2 dev parity: compile+runtime namespace is mcp — real
            // ForgeGradle runs srg-named forgeBin + mcp-compiled mods
            // bridged by FML's DeobfuscationTransformer; unimined's
            // single-namespace dev jar needs no such bridge
            searge()
            mcp("stable", "39-1.12")
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
