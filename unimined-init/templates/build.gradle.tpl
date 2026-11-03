plugins {
    java
    @@PLUGIN_DECL@@
}

@@TOOLCHAIN@@
repositories {
    mavenCentral()
@@REPOS@@
}

unimined {
    minecraft {
@@MINECRAFT_BODY@@
    }
@@MOD_BLOCK@@
}

dependencies {
    // mods on the classpath — remapped onto your mappings
    // modImplementation("maven:coord")
    // modCompileOnly(...) / modLocalRuntime(...)
    // unimined.loaderApi("fabric", "0.92.2+1.20.1") — loader api coords
    // (also records the api modid into mod { depends } automatically)
}

@@EXTRAS@@