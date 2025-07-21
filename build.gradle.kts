import org.gradle.api.tasks.testing.logging.TestLogEvent
import xyz.wagyourtail.commons.gradle.extendsDependenciesFrom
import xyz.wagyourtail.commons.gradle.includesFrom

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.dokka)
    `java-gradle-plugin`
    `maven-publish`
}

val javaVersion = libs.versions.java.get().toInt()

commons {
    autoGroup()
    autoName()
    autoVersion()
}

allprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "java-gradle-plugin")
    apply(plugin = "maven-publish")

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))

        withSourcesJar()
        withJavadocJar()
    }

    kotlin.jvmToolchain(javaVersion)

    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://maven.wagyourtail.xyz/releases")
        maven("https://maven.wagyourtail.xyz/snapshots")
        maven("https://maven.neoforged.net/releases")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
    }


    val api: SourceSet by sourceSets.creating {
        extendsDependenciesFrom(sourceSets.main.get())
    }

    val main: SourceSet by sourceSets.getting {
        includesFrom(api)
    }

    val test: SourceSet by sourceSets.getting {
        extendsDependenciesFrom(main)
        includesFrom(api)
    }

    tasks.jar {
        from(
            api.output,
            main.output
        )
    }

    val sourcesJar by tasks.getting(Jar::class) {
        from(
            sourceSets["api"].allSource,
            sourceSets["main"].allSource
        )
    }

    val apiJar by tasks.registering(Jar::class) {
        from(sourceSets["api"].output)
    }

    val apiSourcesJar by tasks.registering(Jar::class) {
        from(sourceSets["api"].allSource)
    }

    tasks.build {
        dependsOn(sourcesJar)
    }

    tasks.test {
        useJUnitPlatform()

        testLogging {
            events.add(TestLogEvent.PASSED)
            events.add(TestLogEvent.SKIPPED)
            events.add(TestLogEvent.FAILED)
        }
    }

    dokka {
        dokkaPublications.html {
            outputDirectory.set(projectDir.resolve("docs/api-docs/"))
        }
        dokkaSourceSets {
            named("main") {
                suppress = true
            }
            named("api") {
                suppress = false
            }
        }
    }

    gradlePlugin {
        plugins {
            register(project.path.replace(":", "-")) {
                id = "xyz.wagyourtail." + project.base.archivesName.get()
                implementationClass = "xyz.wagyourtail.${base.archivesName.get().replace("-", ".")}.GradlePlugin"
            }
        }
    }

    publishing {
        repositories {
            maven {
                name = "WagYourMaven"
                url = uri("https://maven.wagyourtail.xyz/" + if (project.hasProperty("version_snapshot")) "snapshots/" else "releases/")
                credentials {
                    username = project.findProperty("mvn.user") as String? ?: System.getenv("USERNAME")
                    password = project.findProperty("mvn.key") as String? ?: System.getenv("TOKEN")
                }
            }
        }
    }

}

dependencies {
    for (p in subprojects) {
        api(project(p.path))
    }
    api(gradleApi())

    api(libs.commons.gradle)
}

tasks.javadoc {
    doFirst {
        file("Writerside/v.list").writeText(
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE vars SYSTEM "https://resources.jetbrains.com/writerside/1.0/vars.dtd">
                <vars>
                    <var name="version" value="${project.version}"/>
                </vars>
            """.trimIndent()
        )
    }
}