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
@@ENV@@
@@MOD_BLOCK@@
}
@@EXTRAS@@
