package xyz.wagyourtail.unimined.mapping.processors

import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingPostProcessor
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableClassMapping
import xyz.wagyourtail.unimined.mapping.element.metadata.SignatureElement
import xyz.wagyourtail.unimined.mapping.jvms.JVMS
import xyz.wagyourtail.unimined.mapping.jvms.four.seven.nine.one.`class`.ClassSignature
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName

/**
 * Builtin [MappingPostProcessor] applying class-signature fixes — loom's
 * `signatureFix` extra: mapping rows cannot carry the correct generic
 * signature for classes like records, so mapping jars ship a fix table
 * (`extras/record_signatures.json`) the remap step applies verbatim.
 *
 * Fixes come from this instance's [fixes] table (or a spec-scoped
 * producer's own processor); they are
 * stamped onto matching class rows as [SignatureElement] metadata —
 * only where the class doesn't already carry a signature, matching
 * loom's `signature == null` fill. Classes named in the table but absent
 * from the tree are ignored. No-ops on data without a mutable UML tree.
 *
 * @param baseNamespace namespace [fixes] keys/values are relative to —
 *  overridden by a contributed view's own `baseNamespace` for its fixes
 * @param fixes class internal name in [baseNamespace] → JVM class
 *  signature (JVMS §4.7.9.1)
 */
class SignatureFixesPostProcessor(
    private val baseNamespace: String? = null,
    private val fixes: Map<String, String> = emptyMap()
) : MappingPostProcessor {

    override val name = "signature-fixes"

    override fun process(data: MappingData, ctx: MappingContext): MappingData {
        val tree = data.view<MappingTree>() as? MutableMappingTree
            ?: return data
        if (baseNamespace == null || fixes.isEmpty()) return data

        val ns = tree.namespaces.firstOrNull { it.name == baseNamespace }
            ?: return data
        applyFixes(tree, ns, fixes)
        return data
    }

    companion object {

        /**
         * Stamp [SignatureElement]s onto [tree] for each `class →
         * signature` entry — only where the class row exists and
         * doesn't already carry a signature (loom's `signature == null`
         * fill). Keys resolve against [baseNamespace].
         */
        fun applyFixes(
            tree: MutableMappingTree,
            baseNamespace: Namespace,
            fixes: Map<String, String>
        ) {
            for ((className, signature) in fixes) {
                val cls = tree.getClass(baseNamespace, InternalName.unchecked(className))
                        as? MutableClassMapping ?: continue
                if (cls.metadata.any { it is SignatureElement }) continue
                cls.addMetadata(SignatureElement(parseSignature(signature), baseNamespace))
            }
        }

        /** Parse a JVMS class signature, falling back to the unchecked form. */
        private fun parseSignature(value: String) = runCatching {
            JVMS.parseClassSignature(value)
        }.getOrElse { ClassSignature.unchecked(value) }

    }

}
