import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.testing.logging.TestLogEvent
import xyz.wagyourtail.commons.gradle.GradleProjectExtension

plugins {
    id("xyz.wagyourtail.commons-gradle") version "1.0.5-SNAPSHOT"
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}

subprojects {
    // mc-launcher is a Gradle-free KMP library — exempt from the
    // kotlin-jvm/java/commons conventions; it sets its own coords
    if (name == "mc-launcher") return@subprojects

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "maven-publish")
    apply(plugin = "xyz.wagyourtail.commons-gradle")

    extensions.configure<GradleProjectExtension>("commons") {
        autoGroup()
        autoVersion()
    }

    val publishedName = if (name.startsWith("${rootProject.name}-")) name else "${rootProject.name}-$name"

    extensions.configure<BasePluginExtension>("base") {
        archivesName.set(publishedName)
    }

    val javaVersion = 17

    extensions.configure<JavaPluginExtension>("java") {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(javaVersion))
        }
        withSourcesJar()
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        jvmToolchain(javaVersion)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events.addAll(listOf(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED))
        }
    }

    extensions.configure<PublishingExtension>("publishing") {
        publications.withType<MavenPublication>().configureEach {
            if (artifactId == project.name) {
                artifactId = publishedName
            }
        }
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
}
