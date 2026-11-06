package xyz.wagyourtail.unimined.minecraft.merge

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import xyz.wagyourtail.unimined.mapping.EnvType

/**
 * Pluggable merge behavior for [JarMerger]. Two hook kinds, composed
 * by [CompositeExtension]:
 *
 *  - **Merge hooks** — `mergeResource`/`mergeClass` are first-match:
 *    extensions run in order, `null` defers to the next; the default
 *    (client-wins / [MemberMerger]) sits at the end of the chain.
 *
 *  - **Transform hooks** — `transformClass` is *all-in-order*:
 *    every extension post-processes the produced [ClassNode] before
 *    serialization. Provenance recorded by the merger (which members
 *    came from which side) is available via [ClassContext.provenance].
 *
 * A divergent-member merger (bytecode side-dispatch, class renames)
 * registers a `mergeClass` ahead of [MemberMerger], records provenance
 * for its dispatch members, and defers the rest — marker stamping and
 * remaining union behavior still compose around it.
 */
interface MergeExtension {

    /** Merge a non-class entry — each side's raw bytes, nullable for
     *  side-absent entries. `null` defers; the client-wins default is
     *  the end of chain. */
    fun mergeResource(
        name: String, client: ByteArray?, server: ByteArray?,
    ): ByteArray? = null

    /** Merge a class entry — each side's parsed node, nullable for
     *  side-only classes. Return the merged node or `null` to defer;
     *  [MemberMerger] is the end of chain. */
    fun mergeClass(
        name: String, client: ClassNode?, server: ClassNode?,
        ctx: MergeExtension.ClassContext,
    ): ClassNode? = null

    /** Post-process a merged node pre-serialization — runs for every
     *  extension in order (annotations, metadata, provenance-driven
     *  rewrites). */
    fun transformClass(
        name: String, node: ClassNode,
        ctx: MergeExtension.ClassContext,
    ) {}

    /** Per-class provenance recorded during merge — which side each
     *  member (and the class itself) came from. Transform extensions
     *  consume this (e.g. side-marker stamping); mergers populate it. */
    class Provenance {
        /** `name+desc` → contributing side, for fields and methods
         *  that exist on only one side of the pair. */
        val fields = mutableMapOf<String, EnvType>()
        val methods = mutableMapOf<String, EnvType>()
        /** Set when the class itself exists on only one side. */
        var classSide: EnvType? = null
    }

    /** Shared machinery passed to class hooks — raw entry bytes for
     *  verbatim fast paths, the member-merger for delegation, and
     *  the per-class provenance record. */
    class ClassContext(
        val clientBytes: ByteArray?,
        val serverBytes: ByteArray?,
        val provenance: Provenance = Provenance(),
    ) {
        /** A member-merger with provenance-recording callbacks
         *  bound — extensions needing union semantics delegate here,
         *  supplying a [ClassMergePolicy] to steer conflict
         *  resolution (e.g. dispatch/rename instead of strict fail). */
        fun classMerger(
            policy: ClassMergePolicy = ClassMergePolicy.STRICT,
        ) = ClassMerger(
            toMergedClass = { _, side -> provenance.classSide = side },
            toMergedFields = { node, side ->
                provenance.fields[(node as FieldNode).name +
                        (node as FieldNode).desc] = side
            },
            toMergedMethods = { node, side ->
                provenance.methods[(node as MethodNode).name +
                        (node as MethodNode).desc] = side
            },
            policy = policy,
        )
    }
}

/** Ordered composition — first non-null merge result wins; every
 *  transform hook runs in order. This is the chain shape itself:
 *  user extensions compose the same way inside `Options.extensions`. */
class CompositeExtension(
    private val extensions: List<MergeExtension>,
) : MergeExtension {

    override fun mergeResource(
        name: String, client: ByteArray?, server: ByteArray?,
    ) = extensions.firstNotNullOfOrNull {
        it.mergeResource(name, client, server)
    }

    override fun mergeClass(
        name: String, client: ClassNode?, server: ClassNode?,
        ctx: MergeExtension.ClassContext,
    ) = extensions.firstNotNullOfOrNull {
        it.mergeClass(name, client, server, ctx)
    }

    override fun transformClass(
        name: String, node: ClassNode, ctx: MergeExtension.ClassContext,
    ) = extensions.forEach { it.transformClass(name, node, ctx) }
}

/**
 * `META-INF/services/` provider lists merge by line-union — client
 *  order first, server-only providers appended — so neither side's
 *  SPI registrations are dropped (e.g. bta's embedded SLF4J → log4j2
 *  binding). Other conflicts defer to the client-wins default.
 */
class ServiceFileMerger : MergeExtension {

    override fun mergeResource(
        name: String, client: ByteArray?, server: ByteArray?,
    ): ByteArray? {
        if (client == null || server == null ||
            !name.startsWith("META-INF/services/")) return null
        val lines = LinkedHashSet<String>()
        for (bytes in listOf(client, server)) {
            bytes.toString(Charsets.UTF_8).lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach(lines::add)
        }
        return lines.joinToString("\n", postfix = "\n").toByteArray()
    }
}

/**
 * Stamps the configured `@SideOnly`-style annotations
 * ([JarMerger.Options.sideMarkers]) on content whose provenance is
 * single-side — FML-era merges (and the binpatch checksums built
 * against them) carry the marker on every single-side
 * class/field/method.
 */
class SideOnlyMarkers(
    private val sideMarkers: List<JarMerger.SideMarker>,
) : MergeExtension {

    private fun mark(annotations: MutableList<AnnotationNode>?,
                     set: (MutableList<AnnotationNode>) -> Unit,
                     side: EnvType) {
        val target = annotations
            ?: mutableListOf<AnnotationNode>().also(set)
        for (marker in sideMarkers) {
            target.add(AnnotationNode("L${marker.annotation};").apply {
                values = listOf("value", arrayOf(
                    "L${marker.enumType};",
                    if (side == EnvType.CLIENT) marker.clientValue
                    else marker.serverValue))
            })
        }
    }

    override fun transformClass(
        name: String, node: ClassNode, ctx: MergeExtension.ClassContext,
    ) {
        if (sideMarkers.isEmpty()) return
        ctx.provenance.classSide?.let { side ->
            mark(node.visibleAnnotations,
                { node.visibleAnnotations = it }, side)
        }
        for (field in node.fields) {
            ctx.provenance.fields[field.name + field.desc]?.let { side ->
                mark(field.visibleAnnotations,
                    { field.visibleAnnotations = it }, side)
            }
        }
        for (method in node.methods) {
            ctx.provenance.methods[method.name + method.desc]
                ?.let { side ->
                    mark(method.visibleAnnotations,
                        { method.visibleAnnotations = it }, side)
                }
        }
    }
}

/**
 * The default class merge — member-wise union via [ClassMerger],
 * recording which members are single-side into
 * [ClassContext.provenance] (markers are stamped separately by
 * [SideOnlyMarkers]). Byte-identical inputs emit verbatim upstream —
 * this merger only sees nodes that actually need merging.
 */
class MemberMerger : MergeExtension {

    override fun mergeClass(
        name: String, client: ClassNode?, server: ClassNode?,
        ctx: MergeExtension.ClassContext,
    ) = ctx.classMerger().accept(client, server)
}
