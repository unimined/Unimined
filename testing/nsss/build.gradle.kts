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
        // nsss has no upstream index — the preset claims
        // provider("conversion") + the minecraft:nsss product and the
        // dist jar is declared inside the block; the conversion
        // provider materializes it through spec.declaredSource
        nsss {
            version = "1.0"
            side = Side.CLIENT
            file("dist.jar")
        }
    }
}
