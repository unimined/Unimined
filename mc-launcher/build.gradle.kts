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
            implementation(libs.minecraftauth)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

// exempted from the root subprojects convention (Gradle-free KMP lib),
// so the WagYourMaven repo is declared here — without it `publish`
// is a silent no-op and unimined-runs' dep 404s on the remote
publishing {
    repositories {
        maven {
            name = "WagYourMaven"
            url = uri(
                "https://maven.wagyourtail.xyz/" +
                        if (project.hasProperty("version_snapshot")) "snapshots/" else "releases/"
            )
            credentials {
                username = (findProperty("mvn.user") as String?)
                    ?: providers.environmentVariable("USERNAME").orNull
                password = (findProperty("mvn.key") as String?)
                    ?: providers.environmentVariable("TOKEN").orNull
            }
        }
    }
}
