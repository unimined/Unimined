pluginManagement {
    includeBuild("../..")
    repositories {
        mavenLocal()
        maven("https://maven.wagyourtail.xyz/releases/")
        maven("https://maven.wagyourtail.xyz/snapshots/")
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
    }
}

rootProject.name = "fabric-mojmap"
