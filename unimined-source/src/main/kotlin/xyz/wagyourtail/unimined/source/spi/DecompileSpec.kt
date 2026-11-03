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

}
