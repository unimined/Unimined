plugins {
    java
    id("xyz.wagyourtail.unimined")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

repositories {
    mavenCentral()
}

unimined {
    minecraft {
        version = "1.20.1"
        mappings {
            // bare builtin channel: the dev artifact is mojmap-named and
            // `remapJar` remaps mojmap -> official for production
            mojmap()
        }
    }
}
