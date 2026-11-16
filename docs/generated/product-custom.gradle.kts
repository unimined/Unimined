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
        version.set(providers.gradleProperty("minecraft_version"))
        maven("com.example:myoverhaul:1.0")
        fabric(providers.gradleProperty("fabric_version").get())
    }

}
