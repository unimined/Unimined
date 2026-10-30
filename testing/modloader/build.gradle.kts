import xyz.wagyourtail.unimined.api.spec.Side

plugins {
    java
    id("xyz.wagyourtail.unimined")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

// Parameterized so one fixture covers every ModLoader patch release:
//   ./gradlew runClient -Pmc=1.2.5 -Pml=1.2.5 -PmcpVer=6.2-1.2.5
//   ./gradlew runClient -Pmc=a1.2.6 -Pml=a1.2.6+build.2 -PmcpVer=2.5-a1.2.6
val mc = providers.gradleProperty("mc").orElse("1.2.5").get()
val ml = providers.gradleProperty("ml").orElse(mc).get()
val mcpVer = providers.gradleProperty("mcpVer")

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.wagyourtail.xyz/releases") {
        metadataSources { mavenPom(); artifact() }
    }
    maven("https://maven.minecraftforge.net/") {
        metadataSources { mavenPom(); artifact() }
    }
}

unimined {
    minecraft {
        version = mc
        // pre-1.3 era: client and server jars are separate; client-only
        side = Side.CLIENT
        loader("jarmod", "0.1.5")
        if (mcpVer.isPresent) {
            mappings {
                mcp("legacy", mcpVer.get())
            }
        }
    }
}

dependencies {
    "jarMod"("risugami:modloader:$ml")
}
