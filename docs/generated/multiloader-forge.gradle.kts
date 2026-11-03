plugins {
    java
    id("xyz.wagyourtail.unimined") version "2.0.0-SNAPSHOT"
}


repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.minecraftforge.net/")
}

unimined {
    minecraft {
        version = "1.20.1"

        loader("forge", "47.2.0")
        mappings {
            yarn(10)
        }
    }
}

dependencies {
    implementation(project(":common"))
    // modImplementation("maven:coord") — remapped mods
}
