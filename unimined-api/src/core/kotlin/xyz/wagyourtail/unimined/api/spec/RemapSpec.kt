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

    /**
     * Classpath jars whose class files live in a *foreign* namespace —
     * they must participate in inheritance analysis under
     * [sourceNamespace]-side names, reached by translating their
     * scanned class info through [ClasspathScanAlias.mappingsFile]
     * rather than by remapping the jar itself. Host wiring uses it to
     * feed the Minecraft artifact into mod-jar batches without a
     * dedicated namespace-translated MC artifact. Aliases already in
     * [sourceNamespace] just index into [classpath] normally.
     */
    val scanAliases: List<ClasspathScanAlias>
        get() = emptyList()

}

/**
 * A classpath jar scanned under a translated namespace — see
 * [RemapSpec.scanAliases]. Execution-time data; hosts serialize the
 * pieces through their own inputs (file collections, properties).
 */
data class ClasspathScanAlias(
    /** The jar to scan. */
    val jar: Path,
    /** The namespace its classes are natively in. */
    val sourceNs: String,
    /** Mapping bundle carrying `sourceNs` -> [RemapSpec.sourceNamespace]. */
    val mappingsFile: Path,
)
