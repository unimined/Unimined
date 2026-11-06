import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi
import java.net.URI

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
                // the clikt umbrella + clikt-mordant klibs both define
                // Context.selfAndAncestors — static-cache linking fails
                // on the duplicate symbol (upstream packaging clash)
                @OptIn(KotlinNativeCacheApi::class)
                disableNativeCache(
                    version = DisableCacheInKotlinVersion.`2_4_21`,
                    reason = "clikt/clikt-mordant klib duplicate symbol: selfAndAncestors",
                    issueUrl = URI("https://github.com/ajalt/clikt/issues/598")
                )
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
            // `clikt` umbrella (mordant-backed DSL surface), not
            // clikt-core — the core artifact lacks the high-level
            // option/argument/echo API the CLI uses
            implementation("com.github.ajalt.clikt:clikt:5.1.0")
        }
    }
}

// fat jar retained for JVM consumers (`java -jar unimined-cli.jar`)
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("unimined-cli")
    archiveClassifier.set("fat")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "xyz.wagyourtail.unimined.cli.Main_jvmKt") }
    // lazy provider loses the builtBy edge — depend on the configuration
    // so project-dep jars (unimined-init) build first
    dependsOn(configurations.named("jvmRuntimeClasspath"))
    from({ configurations.named("jvmRuntimeClasspath").get()
        .map { if (it.isDirectory) it else zipTree(it) } })
    from(kotlin.targets["jvm"].compilations["main"].output.allOutputs)
}
