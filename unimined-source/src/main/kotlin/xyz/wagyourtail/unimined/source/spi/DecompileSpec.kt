package xyz.wagyourtail.unimined.source.spi

import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/**
 * What a [DecompilerEngine] is asked to do: decompile [input] (the
 * decompile-surface fork of the dev jar — never the runtime artifact)
 * into a sources jar.
 */
interface DecompileSpec {

    /** The jar to decompile. */
    val input: Provider<RegularFile>

    /** Classpath entries the decompiler resolves references against. */
    val classpath: FileCollection

    /** Engine-specific options (vineflower prefs, CFR flags, ...). */
    val options: Map<String, String>

    /** JVM args for the decompiler's forked JVM. */
    val jvmArgs: List<String>
        get() = emptyList()

    /** Java major version the forked JVM resolves through the toolchain
     *  service — `null`/blank runs on the build JVM. */
    val javaVersion: String?
        get() = null

    /** Raw CLI args appended after the `-key=value` options. */
    val args: List<String>
        get() = emptyList()

    /** The external engine's decompiler coordinate — set by
     *  `source { generator(...) }`/`dependency.set(...)`. */
    val dependency: String?
        get() = null

}
