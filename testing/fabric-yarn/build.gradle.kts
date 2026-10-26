plugins {
    java
    id("xyz.wagyourtail.unimined")
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
