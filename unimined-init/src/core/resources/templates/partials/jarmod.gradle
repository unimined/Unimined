
val client by sourceSets.creating
val server by sourceSets.creating

tasks.register<Jar>("clientJar") {
    from(client.output)
    archiveClassifier.set("client")
    manifest {
        attributes(
            "JarModAgent-Transforms" to "@@MODID@@-client.transform"
        )
    }
}

tasks.register<Jar>("serverJar") {
    from(server.output)
    archiveClassifier.set("server")
    manifest {
        attributes(
            "JarModAgent-Transforms" to "@@MODID@@-server.transform"
        )
    }
}
