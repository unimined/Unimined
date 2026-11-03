plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

/**
 * Standalone launch library — no Gradle imports. JVM is the primary
 * target; JS/native targets stay enabled so a standalone launcher can be
 * built on it later. In-repo for now; splitting out is mechanical since
 * nothing here touches the Gradle API.
 */
group = "xyz.wagyourtail.unimined.mc-launcher"

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }
    sourceSets {
        jvmMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}
