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
        bta {
            version = "7.3_04"
            side = Side.MERGED
            // optional loader layered on the dist — the bta-fork
            // fabric-loader from the signalum maven
            loader("bta-babric", "0.18.4-bta.11")
        }
    }
}
