package xyz.wagyourtail.unimined.remapper.uml.ext

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.ext.FieldNameAndDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.ext.MethodNameAndDescriptor
import xyz.wagyourtail.unimined.mapping.remapper.asm.AsmExtensionFactory
import xyz.wagyourtail.unimined.mapping.remapper.asm.AsmRemapper
import xyz.wagyourtail.unimined.mapping.remapper.asm.Options
import xyz.wagyourtail.unimined.remapper.uml.ext.Fixtures.classBytes
import xyz.wagyourtail.unimined.remapper.uml.ext.Fixtures.cls
import xyz.wagyourtail.unimined.remapper.uml.ext.Fixtures.named
import xyz.wagyourtail.unimined.remapper.uml.ext.Fixtures.obf
import xyz.wagyourtail.unimined.remapper.uml.ext.Fixtures.readClasses
import xyz.wagyourtail.unimined.remapper.uml.ext.Fixtures.treeOf
import xyz.wagyourtail.unimined.remapper.uml.ext.Fixtures.writeJar
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Pre-mixin LiteLoader bakes its injection tables as `<clinit>` string
 * constants — `Obf(mcpClass, obfClass)` for classes and
 * `Obf(srgMember, obfMember)` for members — which a plain bytecode remap
 * leaves untouched. [LiteloaderObfTableExtension] rewrites both columns to
 * the target namespace: class entries via the obf second arg, member
 * entries via the globally-unique intermediate name (owner-free) or the
 * `MethodInfo(Obf,Obf)` use site that pairs a member constant with its
 * owner.
 */
class LiteloaderObfTableTest {

    private val dir = createTempDirectory("liteloader-obf-table")

    private val OBF = "com/mumfrey/liteloader/core/runtime/Obf"
    private val METHODS = "com/mumfrey/liteloader/core/runtime/Methods"
    private val METHOD_INFO =
        "com/mumfrey/liteloader/transformers/event/MethodInfo"
    private val OVERLAY = "com/example/Overlay"
    private val OBFUSCATED = "Lcom/mumfrey/liteloader/transformers/Obfuscated;"

    // srg column — stands in for whatever intermediate ns a real tree has
    private val srg = Namespace("searge")

    private fun tree() = treeOf(obf, srg, named).apply {
        cls("bao", "net/minecraft/client/Minecraft").apply {
            method(mapOf(
                obf to MethodNameAndDescriptor.unchecked("l;()V"),
                srg to MethodNameAndDescriptor.unchecked("func_71407_l;()V"),
                named to MethodNameAndDescriptor.unchecked("runTick;()V")))
            field(mapOf(
                obf to FieldNameAndDescriptor.unchecked("z;Lqi;"),
                srg to FieldNameAndDescriptor.unchecked("field_71424_I;Lqi;"),
                named to FieldNameAndDescriptor.unchecked("mcProfiler;Lnet/minecraft/profiler/Profiler;")))
        }
        cls("bmg", "net/minecraft/client/shader/Framebuffer").apply {
            method(mapOf(
                obf to MethodNameAndDescriptor.unchecked("a;(II)V"),
                srg to MethodNameAndDescriptor.unchecked("func_148009_a;(II)V"),
                named to MethodNameAndDescriptor.unchecked("framebufferRender;(II)V")))
            // `z` also names this field (→ a DIFFERENT named target) — a
            // genuinely ambiguous candidate that must stay verbatim
            field(mapOf(
                obf to FieldNameAndDescriptor.unchecked("z;I"),
                named to FieldNameAndDescriptor.unchecked("someInt;I")))
        }
        cls("qi", "net/minecraft/profiler/Profiler")
    }

    /** `new <type>(str...) putstatic <field>` window inside `<clinit>` */
    private fun InsnList.obfCtor(type: String, field: String, vararg args: String) {
        add(TypeInsnNode(Opcodes.NEW, type))
        add(InsnNode(Opcodes.DUP))
        args.forEach { add(LdcInsnNode(it)) }
        val desc = "(" + "Ljava/lang/String;".repeat(args.size) + ")V"
        add(MethodInsnNode(Opcodes.INVOKESPECIAL, type, "<init>", desc, false))
        add(FieldInsnNode(Opcodes.PUTSTATIC, type, field, "L$type;"))
    }

    /** `new MethodInfo(<ownerObf>, <memberObf>, Void.TYPE, new Object[]{int,int?})` */
    private fun InsnList.methodInfoCtor(ownerField: String, memberField: String, vararg argTypes: String) {
        add(TypeInsnNode(Opcodes.NEW, METHOD_INFO))
        add(InsnNode(Opcodes.DUP))
        add(FieldInsnNode(Opcodes.GETSTATIC, OBF, ownerField, "L$OBF;"))
        add(FieldInsnNode(Opcodes.GETSTATIC, OBF, memberField, "L$OBF;"))
        add(FieldInsnNode(Opcodes.GETSTATIC, "java/lang/Void", "TYPE", "Ljava/lang/Class;"))
        add(IntInsnNode(Opcodes.BIPUSH, argTypes.size))
        add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"))
        argTypes.forEachIndexed { i, t ->
            add(InsnNode(Opcodes.DUP))
            add(IntInsnNode(Opcodes.BIPUSH, i))
            add(FieldInsnNode(Opcodes.GETSTATIC, t, "TYPE", "Ljava/lang/Class;"))
            add(InsnNode(Opcodes.AASTORE))
        }
        add(MethodInsnNode(Opcodes.INVOKESPECIAL, METHOD_INFO, "<init>",
            "(L$OBF;L$OBF;Ljava/lang/Object;[Ljava/lang/Object;)V", false))
        add(FieldInsnNode(Opcodes.PUTSTATIC, METHODS, memberField, "L$METHOD_INFO;"))
    }

    private fun obfTableBytes(): ByteArray = classBytes(OBF) {
        access = access or Opcodes.ACC_SUPER
        listOf("Minecraft", "Framebuffer", "runTick", "minecraftProfiler",
            "framebufferRender", "unobfClass", "init")
            .forEach { fields.add(FieldNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, it, "L$OBF;", null, null)) }
        methods.add(MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
            instructions.apply {
                obfCtor(OBF, "Minecraft", "net.minecraft.client.Minecraft", "bao")
                obfCtor(OBF, "Framebuffer", "net.minecraft.client.shader.Framebuffer", "bmg")
                obfCtor(OBF, "runTick", "func_71407_l", "l")
                obfCtor(OBF, "minecraftProfiler", "field_71424_I", "z")
                // member whose first column is NOT unique — resolves only
                // via the MethodInfo owner pairing
                obfCtor(OBF, "framebufferRender", "framebufferRender", "a")
                obfCtor(OBF, "unobfClass", "com.mumfrey.liteloader.client.CallbackProxyClient")
                obfCtor(OBF, "init", "<init>")
                add(InsnNode(Opcodes.RETURN))
            }
        })
    }

    private fun methodsBytes(): ByteArray = classBytes(METHODS) {
        methods.add(MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
            instructions.apply {
                methodInfoCtor("Minecraft", "runTick")
                methodInfoCtor("Framebuffer", "framebufferRender",
                    "java/lang/Integer", "java/lang/Integer")
                add(InsnNode(Opcodes.RETURN))
            }
        })
    }

    private fun obfAnn(vararg values: String) =
        AnnotationNode(OBFUSCATED).apply {
            this.values = listOf("value", values.toList())
        }

    private fun stubMethod(name: String, desc: String, vararg values: String) =
        MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null).apply {
            visibleAnnotations = listOf(obfAnn(*values))
            visitCode()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 1)
            visitEnd()
        }

    private fun overlayBytes(): ByteArray = classBytes(OVERLAY) {
        fields.add(FieldNode(Opcodes.ACC_PUBLIC, "timer", "Lqi;", null, null).apply {
            visibleAnnotations = listOf(obfAnn("field_71424_I", "z"))
        })
        // unique candidates — both columns resolve to runTick
        methods.add(stubMethod("resize", "()V", "func_71407_l", "l"))
        // dst-named candidate — already valid, must not be rewritten
        methods.add(stubMethod("runTickAlready", "()V", "runTick"))
        // never an MC member — passes through verbatim
        methods.add(stubMethod("marker", "()V", "<init>", "getTimer"))
    }

    private fun remap(): Path {
        val input = dir.resolve("liteloader.jar")
        val out = dir.resolve("liteloader-named.jar")
        writeJar(input, mapOf(
            "bao.class" to classBytes("bao"),
            "bmg.class" to classBytes("bmg"),
            "$OBF.class" to obfTableBytes(),
            "$METHODS.class" to methodsBytes(),
            "$OVERLAY.class" to overlayBytes(),
        ))
        AsmRemapper.remap(input, out, tree(), obf, named, Options(
            extensions = listOf(
                AsmExtensionFactory { ctx -> LiteloaderObfTableExtension(ctx) }),
        ))
        return out
    }

    /** extract `new Obf(...)` LDC args per putstatic field from the output jar */
    private fun obfArgs(out: Path): Map<String, List<String>> {
        ZipFile(out.toFile()).use { zin ->
            val node = ClassNode()
            ClassReader(zin.getInputStream(zin.getEntry("$OBF.class"))).accept(node, 0)
            val clinit = node.methods.first { it.name == "<clinit>" }
            val result = mutableMapOf<String, List<String>>()
            var ldcs: MutableList<String>? = null
            for (insn in clinit.instructions) {
                when (insn) {
                    is TypeInsnNode -> if (insn.opcode == Opcodes.NEW) ldcs = mutableListOf()
                    is LdcInsnNode -> if (ldcs != null && insn.cst is String)
                        ldcs!!.add(insn.cst as String)
                    is FieldInsnNode -> if (ldcs != null &&
                        insn.opcode == Opcodes.PUTSTATIC) {
                        result[insn.name] = ldcs!!
                        ldcs = null
                    }
                }
            }
            return result
        }
    }

    @Test
    fun `class constants are rewritten to the target namespace`() {
        val args = obfArgs(remap())
        assertEquals(
            listOf("net.minecraft.client.Minecraft", "net/minecraft/client/Minecraft"),
            args["Minecraft"])
    }

    @Test
    fun `member constants resolve via the unique srg column`() {
        val args = obfArgs(remap())
        assertEquals(listOf("runTick", "runTick"), args["runTick"])
        assertEquals(listOf("mcProfiler", "mcProfiler"), args["minecraftProfiler"])
    }

    @Test
    fun `member constants resolve via MethodInfo owner pairing`() {
        val args = obfArgs(remap())
        assertEquals(listOf("framebufferRender", "framebufferRender"),
            args["framebufferRender"])
    }

    @Test
    fun `unobfuscated constants pass through verbatim`() {
        val args = obfArgs(remap())
        assertEquals(listOf("com.mumfrey.liteloader.client.CallbackProxyClient"), args["unobfClass"])
        assertEquals(listOf("<init>"), args["init"])
    }

    // ---- @Obfuscated overlay-stub annotation values ----

    private fun obfValues(node: AnnotationNode): List<*> {
        val i = node.values.indexOf("value")
        assertTrue(i >= 0)
        @Suppress("UNCHECKED_CAST")
        return node.values[i + 1] as List<String>
    }

    private fun overlayAnn(member: String): AnnotationNode {
        val node = readClasses(remap()).getValue(OVERLAY)
        val m = node.methods.first { it.name == member }
        return m.visibleAnnotations.first { it.desc == OBFUSCATED }
    }

    @Test
    fun `obfuscated annotation values rewrite unique candidates to target names`() {
        // srg `func_71407_l` and obf `l` are each globally unique in the
        // tree — both columns become the target name so whichever variant
        // the runtime consults resolves
        assertEquals(listOf("runTick", "runTick"), obfValues(overlayAnn("resize")))
    }

    @Test
    fun `obfuscated annotation values skip ambiguous candidates`() {
        // `z` names two members with different targets — left verbatim
        val node = readClasses(remap()).getValue(OVERLAY)
        val ann = node.fields.first { it.name == "timer" }
            .visibleAnnotations.first { it.desc == OBFUSCATED }
        assertEquals(listOf("mcProfiler", "z"), obfValues(ann))
    }

    @Test
    fun `obfuscated annotation values leave non-member and dst strings alone`() {
        assertEquals(listOf("runTick"), obfValues(overlayAnn("runTickAlready")))
        assertEquals(listOf("<init>", "getTimer"), obfValues(overlayAnn("marker")))
    }
}
