package xyz.wagyourtail.unimined.mapping.processors

import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingPostProcessor
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.tree.impl.stripParamPrefix

/**
 * Builtin [MappingPostProcessor] stripping collision-marker prefixes
 * from layered parameter names — loom's
 * `ParchmentPrefixStripingMappingVisitor` semantics, delegated to
 * UML's [stripParamPrefix]: a name like `pFoo` (Parchment marks its
 * parameters with a `p` prefix) becomes `foo`. The prefix character
 * must be followed by an uppercase letter to qualify, and the
 * following letter is lowercased after the strip.
 *
 * @param prefix the collision-marker character to strip (`p` for
 *  Parchment); names shorter than two characters are never stripped
 * @param namespaces namespaces to strip in, or `null` for all — layer
 *  params land in the namespace the layer contributes (Parchment params
 *  arrive under `mojmap`), and propagate to filled cells, so the default
 *  strips everywhere the pattern appears
 */
class ParamPrefixStripPostProcessor(
    private val prefix: Char = 'p',
    private val namespaces: Set<String>? = null
) : MappingPostProcessor {

    override val name = "param-prefix-strip"

    override fun process(data: MappingData, ctx: MappingContext): MappingData {
        val tree = data.view<MappingTree>() as? MutableMappingTree
            ?: return data
        tree.stripParamPrefix(
            prefix,
            namespaces?.mapTo(mutableSetOf()) { Namespace(it) }
        )
        return data
    }

}
