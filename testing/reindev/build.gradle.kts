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
}

unimined {
    minecraft {
        // total-conversion dist: the dist jar IS the MC artifact —
        // the preset claims provider("conversion") + the
        // minecraft:reindev product; a loader may layer on top
        reindev {
            version = "2.9_03"
            side = Side.SERVER
            // blank version → newest pair on the CDN version map
            // (2.0-alpha40); pre-2.9 era → common + server artifacts
            loader("foxloader")
        }
    }
}
