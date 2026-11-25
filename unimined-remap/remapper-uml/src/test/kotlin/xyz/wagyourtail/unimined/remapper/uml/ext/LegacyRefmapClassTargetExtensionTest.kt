package xyz.wagyourtail.unimined.remapper.uml.ext

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import xyz.wagyourtail.unimined.mapping.jvms.ext.MethodNameAndDescriptor
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

    // ---- nested injection-point member selectors (the rift hooks shape) ----

    private val hooksMixin = "org/dimdev/rift/mixin/hook/client/MixinMinecraft"
    private val compileMember =
        "Lnet/minecraft/resources/ResourcePackList;addPackFinder(Lnet/minecraft/resources/IPackFinder;)V"
    private val devMember =
        "Lnet/minecraft/resource/pack/repository/PackRepository;addSource(Lnet/minecraft/resource/pack/repository/RepositorySource;)V"

    /** tree rows for the official-space canonical ref `Lux;a(Luy;)V` the
     *  rift hooks refmap ships — `ux` is PackRepository, `uy` RepositorySource */
    private fun memberExtension(): LegacyRefmapClassTargetExtension {
        val tree = Fixtures.treeOf()
        tree.clazz(mapOf(
            Fixtures.obf to InternalName.unchecked("ux"),
            Fixtures.named to InternalName.unchecked("net/minecraft/resource/pack/repository/PackRepository"),
        )).method(mapOf(
            Fixtures.obf to MethodNameAndDescriptor.unchecked("a;(Luy;)V"),
            Fixtures.named to MethodNameAndDescriptor.unchecked(
                "addSource;(Lnet/minecraft/resource/pack/repository/RepositorySource;)V"),
        ))
        tree.clazz(mapOf(
            Fixtures.obf to InternalName.unchecked("uy"),
            Fixtures.named to InternalName.unchecked("net/minecraft/resource/pack/repository/RepositorySource"),
        ))
        return LegacyRefmapClassTargetExtension(
            ExtensionContext(tree, Fixtures.obf, Fixtures.named))
    }

    /** the exact rift hooks shape: `@Redirect(method=["<init>"], at=@At(
     *  value="INVOKE", target=<compile-space member string>, ordinal=1))` —
     *  the member selector only ever appears inside the *nested* `@At` */
    private fun rewriteNested(): AnnotationNode {
        val ext = memberExtension()
        ext.analyzeResource("mixins.rift.refmap.json", """
            {"mappings":{"$hooksMixin":{"$compileMember":"Lux;a(Luy;)V"}}}
        """.trimIndent().toByteArray())
        val at = AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;").apply {
            values = mutableListOf<Any>("value", "INVOKE", "target", compileMember, "ordinal", 1)
        }
        val redirect = AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Redirect;").apply {
            values = mutableListOf<Any>("method", listOf("<init>"), "at", at)
        }
        val input = ClassNode().apply { name = hooksMixin }
        val output = ClassNode().apply {
            name = hooksMixin
            methods.add(MethodNode(Opcodes.ACC_PRIVATE, "onInit", "()V", null, null).apply {
                visibleAnnotations = mutableListOf(redirect)
            })
        }
        ext.postApply(input, output, null)
        return at
    }

    @Test
    fun `nested injection-point targets bridge through the shipped refmap`() {
        val at = rewriteNested()
        val i = at.values.indexOf("target")
        assertEquals(devMember, at.values[i + 1],
            "nested @At target must bridge to the dev namespace — " +
                "recursing with empty entries leaves it compile-space")
    }

    @Test
    fun `nested rewrite preserves the other pointcut values`() {
        val at = rewriteNested()
        val v = at.values.indexOf("value")
        val o = at.values.indexOf("ordinal")
        assertEquals("INVOKE", at.values[v + 1])
        assertEquals(1, at.values[o + 1])
    }
}
