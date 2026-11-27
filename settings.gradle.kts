pluginManagement {
    repositories {
        mavenLocal()
        maven("https://maven.wagyourtail.xyz/releases/")
        maven("https://maven.wagyourtail.xyz/snapshots/")
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("xyz.wagyourtail.commons-gradle") version "1.1.2"
}

rootProject.name = "unimined"

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        maven("https://maven.wagyourtail.xyz/releases/")
        maven("https://maven.wagyourtail.xyz/snapshots/")
        mavenCentral()
        maven("https://maven.neoforged.net/releases")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.firstdark.dev/releases")
        maven("https://maven.ornithemc.net/releases") {
            // preen/condor — remapper-uml's upstream Ornithe jar processors
            content { includeGroup("net.ornithemc") }
        }
        maven("https://maven.kikugie.dev/releases") {
            // stonecutter — adapter-stonecutter's compileOnly dep
            content { includeGroup("dev.kikugie") }
        }
        maven("https://libraries.minecraft.net/") {
            // launchwrapper — transform-forge's compileOnly dep
            content { includeGroup("net.minecraft") }
        }
        gradlePluginPortal()
    }
}

commons.autoSubprojects()
