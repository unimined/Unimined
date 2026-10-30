plugins {
    java
    id("xyz.wagyourtail.unimined")
}
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}
repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.minecraftforge.net/") { metadataSources { mavenPom(); artifact() } }
}
unimined {
    minecraft {
        version = "1.4.7"
        loader("fg1", "0.1.5")
    }
}
dependencies {
    "forge"("net.minecraftforge:forge:1.4.7-6.6.2.534:universal@zip")
}
