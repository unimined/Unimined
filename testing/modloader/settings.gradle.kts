pluginManagement {
    includeBuild("../..")
    repositories {
        mavenLocal()
        maven("https://maven.wagyourtail.xyz/releases/")
        maven("https://maven.wagyourtail.xyz/snapshots/")
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "modloader"
