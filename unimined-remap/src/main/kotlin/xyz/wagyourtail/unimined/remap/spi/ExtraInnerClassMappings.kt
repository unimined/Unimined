package xyz.wagyourtail.unimined.remap.spi

import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.tree.impl.renest
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * 1.x `addExtraInnerClassMappings`: inner classes *added* by a patch
 * (present in [postPatched], absent in [prePatched]) get no mapping row
 * of their own. Each is inserted with just its source-namespace name,
 * then a single [MutableMappingTree.renest] pass derives every other
 * namespace's name from the mapped outer class (recursively through
 * nested outers, and with proper `InnerClassElement` metadata —
 * strictly better than 1.x's single-level append). No-op when the
 * resolved tree isn't mutable.
 */
fun addExtraInnerClassMappings(prePatched: Path, postPatched: Path, ctx: StepContext) {
    val tree = ctx.mappings?.view(MappingTree::class.java) as? MutableMappingTree ?: return
    val srcNs = Namespace(ctx.spec.sourceNamespace)
    val pre = classNames(prePatched)
    val added = (classNames(postPatched) - pre).sorted()
    val innerOnly = added.filter { "$" in it }
    if (innerOnly.isEmpty()) return
    for (className in innerOnly) {
        if (tree.getClass(srcNs, InternalName.unchecked(className)) != null) continue
        tree.clazz(mapOf(srcNs to InternalName.unchecked(className)))
    }
    tree.renest(srcNs, tree.namespaces.toSet() - srcNs)
    ctx.log.lifecycle(
        "renested ${innerOnly.size} added inner classes: $innerOnly"
    )
}

private fun classNames(jar: Path): Set<String> =
    JarFile(jar.toFile()).use { j ->
        j.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".class") }
            .map { it.name.removeSuffix(".class") }
            .toSet()
    }
