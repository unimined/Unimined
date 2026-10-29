package xyz.wagyourtail.unimined.mapping.processors

import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingPostProcessor
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableParameterMapping
import xyz.wagyourtail.unimined.mapping.jvms.four.two.two.UnqualifiedName
import java.util.*

/**
 * Builtin [MappingPostProcessor] stripping collision-marker prefixes
 * from layered parameter names — loom's
 * `ParchmentPrefixStripingMappingVisitor` semantics: a name like `pFoo`
 * (Parchment marks its parameters with a `p` prefix) becomes `foo`.
 * The prefix character must be followed by an uppercase letter to
 * qualify, and the following letter is lowercased after the strip.
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
        val nsFilter = namespaces?.mapTo(mutableSetOf()) { Namespace(it) }
        fun strip(names: Map<Namespace, UnqualifiedName>): Map<Namespace, UnqualifiedName> {
            var changed = false
            val out = names.toMutableMap()
            for ((ns, name) in names) {
                if (nsFilter != null && ns !in nsFilter) continue
                val stripped = stripName(name.value) ?: continue
                out[ns] = UnqualifiedName.unchecked(stripped)
                changed = true
            }
            return if (changed) out else names
        }
        for (cls in tree.classes) {
            for (method in cls.methods) {
                for (param in method.parameters) {
                    (param as? MutableParameterMapping)?.setNames(strip(param.names))
                }
            }
            for (wildcard in cls.wildcards) {
                for (param in wildcard.parameters) {
                    (param as? MutableParameterMapping)?.setNames(strip(param.names))
                }
            }
        }
        return data
    }

    /**
     * Loom `stripMethodArg`: `<prefix><Uppercase>rest` → `<lowercase>rest`;
     * `null` when the name doesn't carry the marker.
     */
    private fun stripName(arg: String): String? {
        if (arg.length > 1 && arg[0] == prefix && arg[1].isUpperCase()) {
            val rest = arg.substring(1)
            return rest.substring(0, 1).lowercase(Locale.ROOT) + rest.substring(1)
        }
        return null
    }

}
