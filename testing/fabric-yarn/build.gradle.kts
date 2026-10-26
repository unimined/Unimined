plugins {
    java
    id("xyz.wagyourtail.unimined")
}

java {
    // 1.20.1 runs on Java 17 — keep the toolchain pinned so the mod's
    // bytecode stays parseable by the loader's bundled ASM
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
            // layers the named namespace on top of fabric's
            // official -> intermediary default hop: the dev artifact is
            // yarn-named, `remapJar` remaps named -> intermediary
            yarn(10)
        }
    }
}
