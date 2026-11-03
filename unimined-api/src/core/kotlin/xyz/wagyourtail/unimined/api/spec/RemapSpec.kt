package xyz.wagyourtail.unimined.api.spec

import java.nio.file.Path

/**
 * A single remap request: take an artifact in [sourceNamespace] and produce
 * it in [targetNamespace]. Multi-hop remaps (obf→intermediary→named) are
 * composed in the mapping tree, not by chaining remap steps.
 *
 * The spec handed to engines is *resolved* data — laziness lives on the
 * host's side (task properties, file collections), not in the spec itself.
 */
interface RemapSpec {

    val sourceNamespace: String

    val targetNamespace: String

    /** Compile/runtime classpath entries the remapper should resolve
     *  references against. */
    val classpath: List<Path>

}
