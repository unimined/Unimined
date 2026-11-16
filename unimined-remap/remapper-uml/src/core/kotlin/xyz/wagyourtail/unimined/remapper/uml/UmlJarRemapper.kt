package xyz.wagyourtail.unimined.remapper.uml

import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spi.JarRemapper
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.envView
import xyz.wagyourtail.unimined.mapping.inheritance.ASMInheritanceTree
import xyz.wagyourtail.unimined.mapping.inheritance.LazyASMInheritanceTree
import xyz.wagyourtail.unimined.mapping.remapper.asm.AsmRemapper
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * [JarRemapper] backed by UniminedMappingLibrary's [AsmRemapper] —
 * ServiceLoader-discovered so artifact-production code outside the
 * remap module (split-side merged environment assembly) can remap
 * jars without a compile dependency on this engine.
 */
class UmlJarRemapper : JarRemapper {

    override fun remap(
        input: Path,
        output: Path,
        mappings: MappingData,
        fns: String,
        tns: String,
        env: String?
    ) {
        val tree = mappings.view<MappingTree>()
            ?: error("mapping data has no MappingTree view")
        // env selects a collapsed env-view of the tree — `ns:env` ∪ `ns`
        // relabeled to `ns` — so the remap itself is an ordinary
        // two-namespace route; env never reaches the remapper.
        val view = if (env == null) tree else tree.envView(env)
        val fnsNs = Namespace(fns)
        // member refs can name an inherited member — `sub.field`
        // where `field` is declared on a supertype. The plain
        // remap overload has no hierarchy lookup, so the ref would
        // stay unmapped while the super's declaration renames —
        // corrupting the output jar. The input-jar scan covers
        // intra-jar supertypes. Classpath-side supers deliberately
        // aren't indexed: members declared on library classes can
        // never have mapping rows, so resolving them is wasted
        // work — same conclusion as fabric-loom#987, which removed
        // Minecraft libraries from the remap classpath entirely.
        runBlocking {
            AsmRemapper.remap(
                input,
                output,
                listOf(view),
                fnsNs,
                Namespace(tns),
                LazyASMInheritanceTree(
                    fnsNs,
                    ASMInheritanceTree.scan(setOf(input)),
                    emptyMap(),
                ),
            )
        }
    }

}
