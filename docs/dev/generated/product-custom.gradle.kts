plugins {
    java
    id("xyz.wagyourtail.unimined") version "2.0.0-SNAPSHOT"
}


repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

unimined {
    custom("mymodpack") {
        version = "1.20.1"
        loader("fabric", "0.16.0")
    }

}
