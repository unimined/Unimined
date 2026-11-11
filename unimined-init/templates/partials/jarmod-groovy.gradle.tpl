
def client = sourceSets.create("client")
def server = sourceSets.create("server")

tasks.register("clientJar", Jar) {
    from client.output
    archiveClassifier.set("client")
    manifest {
        attributes("JarModAgent-Transforms": "@@MODID@@-client.transform")
    }
}

tasks.register("serverJar", Jar) {
    from server.output
    archiveClassifier.set("server")
    manifest {
        attributes("JarModAgent-Transforms": "@@MODID@@-server.transform")
    }
}
