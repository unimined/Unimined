package xyz.wagyourtail.unimined.mapping.processors

import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingPostProcessor
import xyz.wagyourtail.unimined.api.mapping.namedNamespaces
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.inheritance.ASMInheritanceTree
import xyz.wagyourtail.unimined.mapping.inheritance.LazyASMInheritanceTree
import xyz.wagyourtail.unimined.mapping.inheritance.bytecodeFill
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ploceus `MappingFiller` parity — bytecode hierarchy fill applied to
 * the composed [MappingTree], delegated to UML's
 * [bytecodeFill] (`LazyASMInheritanceTree` + `MemberGroups` +
 * `ResolvingMappingTree` materialization) so the filled names equal
 * what `uml:remap` writes at jar time. Each entry of [jars] binds a
 * product jar to the tree namespace its classes are named in; mapped
 * member names propagate through the real class hierarchy —
 * synthesizing rows for tree-absent product classes — so downstream
 * remap does not break references. This *extends* the name copying
 * the composer's `fillMissingNames` pass performs: that pass fills
 * cells on rows already present, while this one creates rows the
 * sources never declared.
 *
 * Destination namespaces are the tree's unqualified columns other
 * than the jar's own base namespace — environment-qualified columns
 * (`official:client`-style) are side-presence markers and are never
 * fill targets, matching `fillMissingNames`. Namespaces the resolved
 * data flags as named (`named`, `feather`, …) get `unifyBridges` —
 * javac bridge methods join their specialized target's member group.
 * Library jars contribute hierarchy edges only and never gain rows.
 *
 * The processor resolves [jars]/[libraries] lazily inside [process]
 * so the spec value stays free of `Project`/`Provider` state — paths
 * are read at execution time only, and missing files are skipped so
 * providers that cannot supply a jar degrade to no fill.
 */
class BytecodeFillPostProcessor(
    /** `tree namespace -> product jar(s)` — resolved at [process]
     *  time; the namespace is the column whose names match the jar's
     *  raw class/member names (typically `official`, or
     *  `official:client`/`official:server` on split-official merged
     *  environments). */
    private val jars: Map<String, () -> List<Path>>,
    /** Runtime library jars — scanned for hierarchy edges and mapped
     *  supertypes only; their classes never gain rows. */
    private val libraries: () -> List<Path> = { emptyList() },
    /** Optional explicit destination-namespace allowlist — `null`
     *  fills every unqualified column outside the jar namespace. */
    private val targets: Set<String>? = null
) : MappingPostProcessor {

    override val name = "bytecode-fill"

    override fun process(data: MappingData, ctx: MappingContext): MappingData {
        val tree = data.view<MappingTree>() as? MutableMappingTree ?: return data
        val named = data.namedNamespaces().toSet()
        val classpathIndex by lazy {
            LazyASMInheritanceTree.index(
                libraries().filter(Files::exists).toSet()
            )
        }
        for ((nsName, jarProvider) in jars) {
            val srcNs = tree.namespaces.firstOrNull { it.name == nsName }
                ?: continue
            val srcBase = srcNs.splitEnv().first
            val paths = jarProvider().filter(Files::exists)
            if (paths.isEmpty()) continue
            val scan = ASMInheritanceTree.scan(paths.toSet())
            // env-qualified columns mark side presence — never fill
            // targets; the jar's own base column is the source side
            val (namedTargets, plainTargets) = tree.namespaces
                .filter { ns ->
                    (targets == null || ns.name in targets) &&
                        ns.splitEnv().second == null &&
                        ns.splitEnv().first != srcBase
                }
                .partition { it.name in named }
            tree.bytecodeFill(
                fns = srcNs,
                targets = namedTargets.toSet(),
                productScans = listOf(scan),
                classpathIndex = classpathIndex,
                unifyBridges = true
            )
            tree.bytecodeFill(
                fns = srcNs,
                targets = plainTargets.toSet(),
                productScans = listOf(scan),
                classpathIndex = classpathIndex,
                unifyBridges = false
            )
        }
        return data
    }

}
