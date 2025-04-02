import jdk.tools.jlink.resources.plugins

pluginManagement {
    repositories {
        mavenLocal()
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
}

rootProject.name = "unimined"