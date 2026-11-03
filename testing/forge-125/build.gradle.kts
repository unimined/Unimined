import xyz.wagyourtail.unimined.api.spec.Side

plugins {
    java
    id("xyz.wagyourtail.unimined")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

repositories {
    mavenLocal()
    mavenCentral()
    // forge maven first: server classifier zip is not mirrored on wagyourtail
    maven("https://maven.minecraftforge.net/") {
        metadataSources { mavenPom(); artifact() }
    }
    maven("https://maven.wagyourtail.xyz/releases") {
        metadataSources { mavenPom(); artifact() }
    }
}

// split-era: Forge ships separate client/server zips and client/server
// obf namespaces are disjoint — pre-1.3 envs are per-side, not merged.
// Flip to SERVER to validate the server path.
val mcSide = Side.CLIENT

unimined {
    minecraft {
        version = "1.2.5"
        side = mcSide
        loader("fg1", "0.1.5")
        mappings {
            mcp("legacy", "6.2-1.2.5")
        }
    }
}

dependencies {
    // side-specific Forge artifacts (pre-1.3: no universal)
    "forge"("net.minecraftforge:forge:1.2.5-3.4.9.171:client@zip")
    "forge"("net.minecraftforge:forge:1.2.5-3.4.9.171:server@zip")
    // Forge 1.2.5 requires Risugami's loader at runtime — client uses
    // plain ModLoader, server uses ModLoaderMP's server jar; both carry
    // obf names in their side's (disjoint) namespace.
    if (mcSide == Side.SERVER) {
        "jarMod"("modloadermp:modloadermp:1.2.5:server")
    } else {
        "jarMod"("risugami:modloader:1.2.5")
    }
}
