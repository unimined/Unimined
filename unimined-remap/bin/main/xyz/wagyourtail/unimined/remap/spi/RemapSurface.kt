package xyz.wagyourtail.unimined.remap.spi

/**
 * The artifact surfaces a remap step pipeline can run on.
 */
enum class RemapSurface {

    /** Binary/runtime artifact — what Minecraft actually runs. */
    JAR,

    /** The jar fed to the decompiler inside `genSources` — a fork of the
     *  runtime jar, so decompile-quality steps (preen, condor, nests)
     *  never touch the runtime artifact. */
    DECOMPILE,

    /** Source jars. */
    SOURCES

}
