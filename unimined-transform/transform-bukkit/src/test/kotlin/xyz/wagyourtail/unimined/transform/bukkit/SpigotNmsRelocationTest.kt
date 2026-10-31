package xyz.wagyourtail.unimined.transform.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.element.mapping.ClassMapping
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.transform.bukkit.internal.SpigotNmsRelocationProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import kotlin.reflect.KClass

/** spigotProd NMS relocation — class names gain the versioned package. */
class SpigotNmsRelocationTest {

    private val specCtx = object : JarProcessor.SpecContext {
        override val productionNamespace = "spigotProd"
        override fun mods(): List<JarProcessor.ModJarInfo> = emptyList()
    }

    private val procCtx = object : JarProcessor.ProcessorContext {
        override val logger = uniminedLogger(
            org.gradle.api.logging.Logging.getLogger(SpigotNmsRelocationTest::class.java))
        override val mappings: MappingData? = null
        override fun mods(): List<JarProcessor.ModJarInfo> = emptyList()
    }

    private fun treeWithSpigot(): Pair<MappingTree, ClassMapping> {
        val official = Namespace("official")
        val prod = Namespace("spigotProd")
        val tree = MappingTree(listOf(official, prod))
        val cls = ClassMapping(mapOf(
            official to InternalName.unchecked("net/minecraft/server/World"),
            prod to InternalName.unchecked("net/minecraft/server/World")
        ))
        @Suppress("UNCHECKED_CAST")
        (tree.classes as MutableList<ClassMapping>).add(cls)
        return tree to cls
    }

    private fun dataFor(tree: MappingTree) = object : MappingData {
        override val viewTypes: Set<KClass<*>> = setOf(MappingTree::class)
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> view(type: Class<T>) =
            if (type == MappingTree::class.java) tree as T else null
    }

    @Test
    fun `spigotProd names relocate into the versioned package`() {
        val (tree, cls) = treeWithSpigot()
        val processor = SpigotNmsRelocationProcessor { "v1_20_R3" }
        val spec = processor.buildSpec(specCtx)!!
        processor.processMappings()!!.transform(dataFor(tree), spec, procCtx)
        val prod = tree.namespaces.first { it.name == "spigotProd" }
        assertEquals(
            "net/minecraft/server/v1_20_R3/World",
            cls.names[prod]!!.value
        )
    }

    @Test
    fun `spec round-trips the version token`() {
        val processor = SpigotNmsRelocationProcessor { "v1_20_R3" }
        val spec = processor.buildSpec(specCtx)!!
        val payload = processor.serializeSpec(spec)
        assertEquals("v1_20_R3", payload)
        assertEquals("v1_20_R3",
            (processor.deserializeSpec(payload) as
                SpigotNmsRelocationProcessor.Spec).versionPackage)
    }
}
