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
}
@@DEPENDENCIES@@
