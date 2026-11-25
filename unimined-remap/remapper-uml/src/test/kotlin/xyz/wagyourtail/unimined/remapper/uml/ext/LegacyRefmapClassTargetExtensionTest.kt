package xyz.wagyourtail.unimined.remapper.uml.ext

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.remapper.ExtensionContext

/** Behavior of the legacy shipped-refmap bridge for reobfuscated
 *  `@Mixin(targets)` strings. */
class LegacyRefmapClassTargetExtensionTest {

    private val mixin =
        "org/dimdev/rift/mixin/hook/MixinEnumConnectionState\$Handshaking"
    private val compileTarget = "net/minecraft/network/EnumConnectionState\$1"
    private val devTarget = "net/minecraft/network/NetworkProtocol\$17055769"

    private fun extension(
        obfClass: String? = "hx\$1"
    ): LegacyRefmapClassTargetExtension {
        val tree = Fixtures.treeOf()
        if (obfClass != null) {
            tree.clazz(mapOf(
                Fixtures.obf to InternalName.unchecked(obfClass),
                Fixtures.named to InternalName.unchecked(devTarget)
            ))
        }
        return LegacyRefmapClassTargetExtension(
            ExtensionContext(tree, Fixtures.obf, Fixtures.named))
    }

    private fun rewrite(
        ext: LegacyRefmapClassTargetExtension,
        refmapValue: String = "hx\$1"
    ): AnnotationNode {
        ext.analyzeResource("mixins.rift.refmap.json", """
            {"mappings":{"$mixin":{"$compileTarget":"$refmapValue"}}}
        """.trimIndent().toByteArray())
        val input = ClassNode().apply { name = mixin }
        val ann = AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;").apply {
            values = mutableListOf<Any>("targets", listOf(compileTarget))
        }
        val output = ClassNode().apply {
            name = mixin
            invisibleAnnotations = mutableListOf(ann)
        }
        ext.postApply(input, output, null)
        return ann
    }

    @Test
    fun `shipped refmap bridges compile-space target through source namespace`() {
        val ann = rewrite(extension())
        @Suppress("UNCHECKED_CAST")
        val targets = ann.values[1] as List<String>
        assertEquals(listOf(devTarget), targets)
    }

    @Test
    fun `foreign refmap class rows do not rewrite annotations`() {
        val ann = rewrite(extension(obfClass = null), "hx\$1")
        @Suppress("UNCHECKED_CAST")
        val targets = ann.values[1] as List<String>
        assertEquals(listOf(compileTarget), targets)
    }
}
