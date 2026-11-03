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
    // loader api helpers (fabricApi-style) are planned for 2.0
}

@@EXTRAS@@