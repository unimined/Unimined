plugins {
    java
    id("xyz.wagyourtail.unimined") version "2.0.0-SNAPSHOT"
}


repositories {
    mavenCentral()

}

unimined {
    minecraft {
        version = "b1.7.3"


        mappings {
            yarn(10)
        }
    }

}

dependencies {
    // mods on the classpath — remapped onto your mappings
    // modImplementation("maven:coord")
    // modCompileOnly(...) / modLocalRuntime(...)
    // unimined.loaderApi("fabric", "0.92.2+1.20.1") — loader api coords
    // (also records the api modid into mod { depends } automatically)
}



val client by sourceSets.creating
val server by sourceSets.creating

tasks.register<Jar>("clientJar") {
    from(client.output)
    archiveClassifier.set("client")
    manifest {
        attributes(
            "JarModAgent-Transforms" to "examplemod-client.transform"
        )
    }
}

tasks.register<Jar>("serverJar") {
    from(server.output)
    archiveClassifier.set("server")
    manifest {
        attributes(
            "JarModAgent-Transforms" to "examplemod-server.transform"
        )
    }
}
