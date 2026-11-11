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
    // unimined.loaderApi@@LOADER_API@@ — loader api coords
    // (also records the api modid into mod { depends } automatically)
}

@@EXTRAS@@