package xyz.wagyourtail.unimined.remap.spi

import xyz.wagyourtail.unimined.api.mapping.ViewMapMappingData
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.tree.impl.DeltaMappingTree
import xyz.wagyourtail.unimined.mapping.tree.impl.renest
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * 1.x `addExtraInnerClassMappings`: inner classes *added* by a patch
 * (present in [postPatched], absent in [prePatched]) get no mapping row
 * of their own. Each is inserted with just its source-namespace name,
 * then a single `renest` pass derives every other namespace's name
 * from the mapped outer class (recursively through nested outers, and
 * with proper `InnerClassElement` metadata — strictly better than
 * 1.x's single-level append). Rows are written into a per-pipeline
 * [DeltaMappingTree] overlay swapped onto the context — the shared
 * resolved tree is never mutated (it is daemon-cached across parallel
 * transforms). No-op when no mapping tree view is present.
 *
 * The stub is keyed on the class's `this_class` internal name — the
 * value the rename lookup probes — not the zip entry path: FG2-era
 * patch records emit obf internals (`ain$22`) inside srg-path entries
 * (`net/minecraft/item/Item$22.class`), so entry-path stubbing lands
 * the name under the wrong column and the obf reference leaks through
 * the rename unmapped.
 */
fun addExtraInnerClassMappings(prePatched: Path, postPatched: Path, ctx: StepContext) {
    val data = ctx.mappings ?: return
    val view = data.view(MappingTree::class.java) ?: return
    val srcNs = Namespace(ctx.spec.sourceNamespace)
    val pre = classNames(prePatched)
    JarFile(postPatched.toFile()).use { jar ->
        val added = jar.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".class") }
            .map { it.name.removeSuffix(".class") }
            .filter { it !in pre }
            .mapNotNull { entry ->
                jar.getJarEntry("$entry.class")
                    ?.let { jar.getInputStream(it).use { s -> s.readBytes() } }
                    ?.let(::thisClass)
            }
            .filter { "$" in it }
            .sorted().toList()
        if (added.isEmpty()) return
        // the pipeline's MappingData is daemon-shared across parallel
        // transforms (`RemapDependencyTransform.dataCache`) — writing the
        // extras into it races a sibling transform's `prepareBatch` emit
        // (ConcurrentModificationException). A delta overlay keeps this
        // pipeline's additions private; downstream steps see it through
        // the swapped context mappings. On a non-swappable context the
        // delta is still used for this call's lookups so the shared tree
        // stays untouched.
        val tree = view as? DeltaMappingTree ?: DeltaMappingTree(view).also { delta ->
            (ctx as? MutableStepContext)?.mappings = ViewMapMappingData(
                data.viewTypes.associateWith { k ->
                    if (k == MappingTree::class) delta as Any else data.view(k.java)!!
                },
                data.properties
            )
        }
        for (name in added) {
            val iname = InternalName.unchecked(name)
            if (tree.getClass(srcNs, iname) != null) continue
            tree.clazz(mapOf(srcNs to iname))
        }
        tree.renest(srcNs, tree.namespaces.toSet() - srcNs)
        ctx.log.lifecycle(
            "renested ${added.size} added inner classes: $added"
        )
    }
}

private fun classNames(jar: Path): Set<String> =
    JarFile(jar.toFile()).use { j ->
        j.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".class") }
            .map { it.name.removeSuffix(".class") }
            .toSet()
    }

/** `this_class` out of raw .class bytes — walk the constant pool to
 *  the class header, then resolve CONSTANT_Class → CONSTANT_Utf8. */
private fun thisClass(b: ByteArray): String? {
    if (b.size < 10 || b[0] != 0xCA.toByte() || b[1] != 0xFE.toByte()
        || b[2] != 0xBA.toByte() || b[3] != 0xBE.toByte()
    ) return null
    fun u2(p: Int) = (b[p].toInt() and 0xFF shl 8) or (b[p + 1].toInt() and 0xFF)
    val cp = arrayOfNulls<Any>(u2(8))
    var i = 10
    var s = 1
    while (s < cp.size) {
        when (b[i].toInt() and 0xFF) {
            1 -> { cp[s] = String(b, i + 3, u2(i + 1), Charsets.UTF_8); i += 3 + u2(i + 1) }
            3, 4 -> i += 5
            5, 6 -> { i += 9; s++ }
            7 -> { cp[s] = u2(i + 1); i += 3 }
            8, 16, 19, 20 -> i += 3
            9, 10, 11, 12, 17, 18 -> i += 5
            15 -> i += 4
            else -> return null
        }
        s++
    }
    return (cp[u2(i + 2)] as? Int)?.let { cp[it] as? String }
}

// ── BEGIN additive region: jar-processor capability hook overload (GAP B) ──
// Added for the `jar-processors` step's `contributesPatchAddedClasses`
// hook (Docs/progress-2.0-completion.md, GAP B addendum). This region is
// intentionally self-contained — it duplicates the row-insertion logic
// above rather than refactoring the binpatch-stream function, so the two
// uncommitted streams stay merge-isolated. Post-commit cleanup can fold
// both bodies into one shared private implementation.

/**
 * Overload of [addExtraInnerClassMappings] for in-place patchers:
 * jar-processor-style steps mutate one jar path in place, so there is
 * no pre-patch jar file to diff against — the host snapshots the jar's
 * class entry names into [prePatchedClassEntries] immediately before
 * the mutation and passes them here. Semantics otherwise match the
 * two-path overload exactly: entries in [postPatched] absent from the
 * snapshot are keyed by their `this_class` internal name (never the
 * zip entry path), filtered to `$` inners, inserted as source-namespace
 * stubs into a per-pipeline [DeltaMappingTree] overlay (the shared tree
 * is never mutated), and renested from their mapped outer class.
 */
fun addExtraInnerClassMappings(prePatchedClassEntries: Set<String>, postPatched: Path, ctx: StepContext) {
    val data = ctx.mappings ?: return
    val view = data.view(MappingTree::class.java) ?: return
    val srcNs = Namespace(ctx.spec.sourceNamespace)
    JarFile(postPatched.toFile()).use { jar ->
        val added = jar.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".class") }
            .map { it.name.removeSuffix(".class") }
            .filter { it !in prePatchedClassEntries }
            .mapNotNull { entry ->
                jar.getJarEntry("$entry.class")
                    ?.let { jar.getInputStream(it).use { s -> s.readBytes() } }
                    ?.let(::thisClass)
            }
            .filter { "$" in it }
            .sorted().toList()
        if (added.isEmpty()) return
        // see the two-path overload for why the overlay matters —
        // the pipeline's MappingData is daemon-shared across parallel
        // transforms; writes must stay private to this pipeline.
        val tree = view as? DeltaMappingTree ?: DeltaMappingTree(view).also { delta ->
            (ctx as? MutableStepContext)?.mappings = ViewMapMappingData(
                data.viewTypes.associateWith { k ->
                    if (k == MappingTree::class) delta as Any else data.view(k.java)!!
                },
                data.properties
            )
        }
        for (name in added) {
            val iname = InternalName.unchecked(name)
            if (tree.getClass(srcNs, iname) != null) continue
            tree.clazz(mapOf(srcNs to iname))
        }
        tree.renest(srcNs, tree.namespaces.toSet() - srcNs)
        ctx.log.lifecycle(
            "renested ${added.size} added inner classes: $added"
        )
    }
}

// ── END additive region: jar-processor capability hook overload (GAP B) ──
