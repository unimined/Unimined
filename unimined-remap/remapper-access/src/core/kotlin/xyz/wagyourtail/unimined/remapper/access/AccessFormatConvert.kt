package xyz.wagyourtail.unimined.remapper.access

import xyz.wagyourtail.unimined.api.util.NoGroovyTwin

import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.access.AccessFormatConvert as UmlConvert
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * AW ↔ AT ↔ legacy-AT document conversion — the implementation moved
 * into UML
 * (`xyz.wagyourtail.unimined.mapping.formats.access.AccessFormatConvert`)
 * so the doc-level converter is reusable outside the Gradle plugin.
 * This object keeps existing unimined call sites (and any external
 * consumers compiled against the old package) source-compatible by
 * delegating each member to the UML object; [Format] aliases UML's
 * enum so `Format` values flow freely between the two surfaces.
 *
 * Callers that don't need comment/layout preservation can pivot
 * through the mapping tree instead — `ATReader.read` →
 * `emit(AWWriter.write)` — which also exposes UML's
 * `resolveAccessConditions` for stamping conditional access flags
 * concrete before emission.
 */
@NoGroovyTwin("access-format conversion engine — programmatic API")
object AccessFormatConvert {

    /** Access-file surface formats — alias of UML's enum. */
    typealias Format = UmlConvert.Format

    /** See [UmlConvert.detect]. */
    fun detect(path: Path?, bytes: ByteArray): Format? =
        UmlConvert.detect(path?.name, bytes)

    /** See [UmlConvert.convert]. */
    fun convert(
        bytes: ByteArray,
        from: Format,
        to: Format,
        tree: MappingTree? = null,
        targetNs: Namespace? = null,
        warn: ((String) -> Unit)? = null
    ): ByteArray = UmlConvert.convert(bytes, from, to, tree, targetNs, warn)

    /** See [UmlConvert.convert]. */
    fun convert(
        file: Path,
        from: Format,
        to: Format,
        tree: MappingTree? = null,
        targetNs: Namespace? = null,
        warn: ((String) -> Unit)? = null
    ): ByteArray = convert(file.readBytes(), from, to, tree, targetNs, warn)

    /** See [UmlConvert.aw2at]. */
    fun aw2at(
        bytes: ByteArray,
        legacy: Boolean = false,
        warn: ((String) -> Unit)? = null
    ): ByteArray = UmlConvert.aw2at(bytes, legacy, warn)

    /** See [UmlConvert.at2aw]. */
    fun at2aw(
        bytes: ByteArray,
        legacy: Boolean,
        tree: MappingTree,
        targetNs: Namespace,
        warn: ((String) -> Unit)? = null
    ): ByteArray = UmlConvert.at2aw(bytes, legacy, tree, targetNs, warn)

    /** See [UmlConvert.mergeAws]. */
    fun mergeAws(
        inputs: List<ByteArray>,
        targetNs: Namespace
    ): ByteArray = UmlConvert.mergeAws(inputs, targetNs)

}
