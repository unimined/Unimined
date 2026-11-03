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
    // lazy provider loses the builtBy edge — depend on the configuration
    // so project-dep jars (unimined-init) build first
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    from(sourceSets.main.get().output)
}
