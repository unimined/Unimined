pluginManagement {
    includeBuild("../..")
    repositories {
        mavenLocal()
        maven("https://maven.wagyourtail.xyz/releases/")
        maven("https://maven.wagyourtail.xyz/snapshots/")
        mavenCentral()
        maven("https://maven.minecraftforge.net/")
        gradlePluginPortal()
    }
}

rootProject.name = "forge-mojmap"
