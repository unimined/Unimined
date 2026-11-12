plugins {
    java
    id("xyz.wagyourtail.unimined") version "2.0.0-SNAPSHOT"
}


repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

unimined {
    custom("myoverhaul") {
        version = providers.gradleProperty("minecraft_version").get()
        maven("com.example:myoverhaul:1.0")
        loader("fabric", "0.16.0")
    }

}
