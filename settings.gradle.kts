pluginManagement {
    repositories {
        mavenLocal()
        maven("https://maven.wagyourtail.xyz/releases/")
        maven("https://maven.wagyourtail.xyz/snapshots/")
        mavenCentral()
        gradlePluginPortal {
            content {
                excludeGroup("org.apache.logging.log4j")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
    id("xyz.wagyourtail.commons-gradle") version "1.0.5-SNAPSHOT"
}

commons.autoSubprojects()

rootProject.name = "unimined"