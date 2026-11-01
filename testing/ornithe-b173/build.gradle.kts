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
    maven("https://maven.fabricmc.net/")
    maven("https://maven.ornithemc.net/releases")
}

unimined {
    minecraft {
        version = "b1.7.3"
        // split-era merged env: gen-1 Ornithe artifacts only exist as
        // -client/-server, so each channel emits both sided artifacts
        // and the composer unions their `official` columns. Flip to
        // CLIENT/SERVER to validate a sided env.
        side = Side.MERGED
        loader("ornithe", "0.19.5")
        mappings {
            // merged-gen2 e2e: gen-2 calamus+feather stack on a
            // merged env — calamus() rides the loader's
            // upstreamMappingChannel and picks up this var lazily.
            // gen-1 enricher artifacts (sided, keyed on `source` ->
            // official:<side> names) stitch pre-merge since the
            // official env columns match.
            ornitheGenVersion = 2
            feather(2,2)
            nests(1)
            sparrow(2)
            raven(2)
        }
    }
}

// gen1 -> gen2 migration check: bridge via official:client/
// official:server env columns after the gen-2 dual relabel
unimined.migrateMappings("main") {
    target {
        calamus(2)
        feather(2,2)
    }
    targetDevNamespace.set("featherGen2")
}
