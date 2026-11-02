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
            version = "2.4.1"
            side = Side.SERVER
        }
    }
}
