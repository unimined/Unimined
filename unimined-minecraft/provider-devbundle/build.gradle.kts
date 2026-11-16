import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

dependencies {
    "coreApi"(project(":unimined-minecraft"))
    "coreApi"(project(":unimined-api"))
    "coreApi"(libs.kotlinx.serialization.json)
    "coreImplementation"(libs.commons.kt)
    api(project(":unimined-minecraft"))
}

gradlePlugin {
    plugins {
        create("provider-devbundle") {
            id = "xyz.wagyourtail.unimined.provider.devbundle"
            implementationClass = "xyz.wagyourtail.unimined.provider.devbundle.GradlePlugin"
        }
    }
}

// the paperclip stand-in embedded in exported dev bundles — a
// Java-8-bytecode class (every era's JVM floor runs it) compiled
// outside the toolchain-17 main source set, then carried as a plain
// class/resource on the module classpath so DevBundleExport can copy
// its bytes into produced `data/paperclip-mojang.jar` entries
val compileBundleBootstrap = tasks.register<JavaCompile>("compileBundleBootstrap") {
    source = fileTree("src/bootstrap/java")
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("classes/java/bundleBootstrap"))
    options.release.set(8)
}
extensions.configure<JavaPluginExtension>("java") {
    sourceSets.named("main") {
        // lands in the jar + module runtime classpath, matching the
        // root build's core-dir pattern
        output.dir(
            mapOf("builtBy" to "compileBundleBootstrap"),
            "build/classes/java/bundleBootstrap"
        )
    }
}
