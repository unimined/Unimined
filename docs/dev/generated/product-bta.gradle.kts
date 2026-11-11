plugins {
    java
    id("xyz.wagyourtail.unimined") version "2.0.0-SNAPSHOT"
}


repositories {
    mavenCentral()

}

unimined {
    bta {
        version = "8.0.1"
        loader("bta-babric", "1.7.6")
    }

}
