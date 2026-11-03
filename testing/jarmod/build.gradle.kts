plugins {
    java
    id("xyz.wagyourtail.unimined")
}
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}
repositories {
    mavenCentral()
    maven("https://maven.minecraftforge.net/")
}
unimined {
    minecraft {
        version = "1.20.1"
        // JarModAgent release being published: 0.1.5
        loader("jarmod", "0.1.5")
        mappings {
            yarn(10)
        }
    }
}
dependencies {
    // a real merged artifact to prove the jarMod merge injects
    "jarMod"("net.java.dev.jna:jna:4.5.2")
}
