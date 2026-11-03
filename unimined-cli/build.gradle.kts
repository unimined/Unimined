plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    linuxX64 {
        binaries {
            executable {
                baseName = "unimined-cli"
                entryPoint = "xyz.wagyourtail.unimined.cli.main"
            }
        }
    }
    js {
        nodejs()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":unimined-init"))
            implementation("com.github.ajalt.clikt:clikt:5.0.3")
        }
    }
}

// fat jar retained for JVM consumers (`java -jar unimined-cli.jar`)
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("unimined-cli")
    archiveClassifier.set("fat")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "xyz.wagyourtail.unimined.cli.MainKt") }
    // lazy provider loses the builtBy edge — depend on the configuration
    // so project-dep jars (unimined-init) build first
    dependsOn(configurations.named("jvmRuntimeClasspath"))
    from({ configurations.named("jvmRuntimeClasspath").get()
        .map { if (it.isDirectory) it else zipTree(it) } })
    from(kotlin.targets["jvm"].compilations["main"].output.allOutputs)
}
