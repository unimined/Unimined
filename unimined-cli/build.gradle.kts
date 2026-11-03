plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("xyz.wagyourtail.unimined.cli.MainKt")
}

dependencies {
    implementation(project(":unimined-init"))
}

// fat jar so `java -jar unimined-cli.jar new ...` works without Gradle
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("unimined-cli")
    archiveClassifier.set("fat")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "xyz.wagyourtail.unimined.cli.MainKt") }
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
}
