package xyz.wagyourtail.unimined.transform.jarmod.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import xyz.wagyourtail.commons.core.logger.JavaLogWrapper
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.transform.jarmod.internal.fg1.FG1FixForgeProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import kotlin.io.path.outputStream

/**
 * The jarmod `jar-processors` family — cross-package access
 * widening, jarmod→base merge, and FG1 dyn-lib stripping — over
 * synthetic ASM-emitted class fixtures.
 */
class JarModProcessorsTest {

    @TempDir
    lateinit var dir: Path

    private val ctx = object : JarProcessor.ProcessorContext {
        override val logger = JavaLogWrapper(
            java.util.logging.Logger.getLogger("jarmod-processors-test"))
        override val mappings: MappingData? = null
        override fun mods() = emptyList<JarProcessor.ModJarInfo>()
    }

    private fun specCtx(artifacts: Map<String, List<Path>> = emptyMap()) =
        object : JarProcessor.SpecContext {
            override val productionNamespace = "official"
            override fun mods() = emptyList<JarProcessor.ModJarInfo>()
            override fun envArtifacts(name: String) =
                artifacts[name] ?: emptyList()
        }

    private fun jar(name: String, entries: Map<String, ByteArray>): Path {
        val out = dir.resolve(name)
        JarOutputStream(out.outputStream()).use { jos ->
            for ((n, b) in entries) {
                jos.putNextEntry(JarEntry(n)); jos.write(b); jos.closeEntry()
            }
        }
        return out
    }

    private fun entries(jar: Path): Map<String, ByteArray> =
        ZipFile(jar.toFile()).use { z ->
            z.entries().toList().filter { !it.isDirectory }.associate {
                it.name to z.getInputStream(it).readBytes()
            }
        }

    private fun classNode(jar: Path, internal: String): ClassNode =
        ZipFile(jar.toFile()).use { z ->
            ClassNode().also {
                ClassReader(z.getInputStream(z.getEntry("$internal.class")))
                    .accept(it, 0)
            }
        }

    private fun writeClass(cn: ClassNode): ByteArray =
        ClassWriter(0).also { cn.accept(it) }.toByteArray()

    private fun shellClass(name: String): ClassNode = ClassNode().apply {
        version = Opcodes.V1_8
        access = Opcodes.ACC_PUBLIC
        this.name = name
        superName = "java/lang/Object"
    }

    // ---------- AccessWideningProcessor ----------

    /** `pkg/a/Base` declares: `m()` protected, `f` package-private,
     *  `n()` protected, `p()` public. */
    private fun baseClass(): ByteArray {
        val cn = shellClass("pkg/a/Base")
        cn.methods.add(MethodNode(Opcodes.ACC_PROTECTED, "m",
            "()V", null, null).apply {
            instructions.add(InsnNode(Opcodes.RETURN)) })
        cn.fields.add(FieldNode(0, "f", "I", null, null))
        cn.methods.add(MethodNode(Opcodes.ACC_PROTECTED, "n",
            "()V", null, null).apply {
            instructions.add(InsnNode(Opcodes.RETURN)) })
        cn.methods.add(MethodNode(Opcodes.ACC_PUBLIC, "p",
            "()V", null, null).apply {
            instructions.add(InsnNode(Opcodes.RETURN)) })
        return writeClass(cn)
    }

    private fun callerClass(name: String, vararg insns:
        org.objectweb.asm.tree.AbstractInsnNode): ByteArray {
        val cn = shellClass(name)
        cn.methods.add(MethodNode(Opcodes.ACC_PUBLIC, "call",
            "()V", null, null).apply {
            insns.forEach { instructions.add(it) }
            instructions.add(InsnNode(Opcodes.RETURN))
        })
        return writeClass(cn)
    }

    private fun wideningJar(): Path = jar("mc.jar", mapOf(
        "pkg/a/Base.class" to baseClass(),
        "pkg/a/Sibling.class" to callerClass("pkg/a/Sibling",
            MethodInsnNode(Opcodes.INVOKEVIRTUAL, "pkg/a/Base",
                "n", "()V")),
        "other/Caller.class" to callerClass("other/Caller",
            MethodInsnNode(Opcodes.INVOKEVIRTUAL, "pkg/a/Base",
                "m", "()V"),
            FieldInsnNode(Opcodes.GETFIELD, "pkg/a/Base",
                "f", "I"),
            MethodInsnNode(Opcodes.INVOKEVIRTUAL, "pkg/a/Base",
                "p", "()V"))))

    @Test
    fun `access-widening promotes cross-package protected and package-private members`() {
        val target = wideningJar()
        AccessWideningProcessor().processJar(
            target, AccessWideningProcessor.Spec(), ctx)
        val cn = classNode(target, "pkg/a/Base")
        val m = cn.methods.single { it.name == "m" }
        assertTrue(m.access and Opcodes.ACC_PUBLIC != 0,
            "cross-package protected call must widen: access=${m.access}")
        val f = cn.fields.single { it.name == "f" }
        assertTrue(f.access and Opcodes.ACC_PUBLIC != 0,
            "cross-package package-private field read must widen")
    }

    @Test
    fun `access-widening leaves same-package and public members alone`() {
        val target = wideningJar()
        AccessWideningProcessor().processJar(
            target, AccessWideningProcessor.Spec(), ctx)
        val cn = classNode(target, "pkg/a/Base")
        val n = cn.methods.single { it.name == "n" }
        assertTrue(n.access and Opcodes.ACC_PROTECTED != 0,
            "same-package call site keeps protected: access=${n.access}")
        assertTrue(n.access and Opcodes.ACC_PUBLIC == 0)
        val p = cn.methods.single { it.name == "p" }
        assertTrue(p.access and Opcodes.ACC_PUBLIC != 0)
    }

    /** Subclass fixture for protected-access cases — optionally carrying a
     *  protected override of `m()`, plus a method that calls the superclass
     *  implementation. */
    private fun subclassClass(name: String = "other/Sub", overrideM: Boolean = false): ByteArray {
        val cn = shellClass(name)
        cn.superName = "pkg/a/Base"
        if (overrideM) {
            cn.methods.add(MethodNode(Opcodes.ACC_PROTECTED, "m",
                "()V", null, null).apply {
                instructions.add(InsnNode(Opcodes.RETURN)) })
        }
        cn.methods.add(MethodNode(Opcodes.ACC_PUBLIC, "callSuper",
            "()V", null, null).apply {
            instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL,
                "pkg/a/Base", "m", "()V"))
            instructions.add(InsnNode(Opcodes.RETURN))
        })
        return writeClass(cn)
    }

    @Test
    fun `access-widening leaves protected superclass access from subclasses alone`() {
        val target = jar("mc.jar", mapOf(
            "pkg/a/Base.class" to baseClass(),
            "other/Sub.class" to subclassClass()))
        AccessWideningProcessor().processJar(
            target, AccessWideningProcessor.Spec(), ctx)
        val m = classNode(target, "pkg/a/Base").methods.single { it.name == "m" }
        assertTrue(m.access and Opcodes.ACC_PROTECTED != 0,
            "protected super call is legal across packages: access=${m.access}")
        assertEquals(0, m.access and Opcodes.ACC_PUBLIC,
            "subclass access must not trigger widening")
    }

    @Test
    fun `access-widening propagates promoted visibility to overrides`() {
        val target = jar("mc.jar", mapOf(
            "pkg/a/Base.class" to baseClass(),
            "other/Sub.class" to subclassClass(overrideM = true),
            "other/Caller.class" to callerClass("other/Caller",
                MethodInsnNode(Opcodes.INVOKEVIRTUAL, "pkg/a/Base",
                    "m", "()V"))))
        AccessWideningProcessor().processJar(
            target, AccessWideningProcessor.Spec(), ctx)
        val baseM = classNode(target, "pkg/a/Base").methods.single { it.name == "m" }
        val subM = classNode(target, "other/Sub").methods.single { it.name == "m" }
        assertTrue(baseM.access and Opcodes.ACC_PUBLIC != 0,
            "unrelated cross-package caller widens the declaration")
        assertTrue(subM.access and Opcodes.ACC_PUBLIC != 0,
            "override must be widened with the declaration: access=${subM.access}")
    }

    @Test
    fun `access-widening no-ops without class entries`() {
        val target = jar("mc.jar",
            mapOf("assets/x.txt" to "x".toByteArray()))
        AccessWideningProcessor().processJar(
            target, AccessWideningProcessor.Spec(), ctx)
        assertEquals(setOf("assets/x.txt"), entries(target).keys)
    }

    // ---------- JarModMergeProcessor ----------

    private fun baseJar(name: String = "base.jar") = jar(name, mapOf(
        "a/A.class" to "base-a".toByteArray(),
        "shared.txt" to "base-shared".toByteArray(),
        "META-INF/MANIFEST.MF" to "mf".toByteArray(),
        "META-INF/keep.txt" to "keep".toByteArray()))

    private fun modJar(name: String = "mod.jar") = jar(name, mapOf(
        "a/A.class" to "mod-a".toByteArray(),
        "mod/Mod.class" to "mod".toByteArray(),
        "_b/B.class" to "replacement".toByteArray(),
        "META-INF/MANIFEST.MF" to "mod-mf".toByteArray(),
        "META-INF/mods.toml" to "toml".toByteArray(),
        "META-INF/TEST.SF" to "sig".toByteArray()))

    @Test
    fun `jarmod merge mod entries win and underscore classes strip`() {
        val mod = modJar(); val base = baseJar()
        val p = JarModMergeProcessor(jarMod = listOf(mod))
        val spec = p.buildSpec(specCtx())!!
        p.processJar(base, spec, ctx)
        val out = entries(base)
        assertEquals("mod-a", out["a/A.class"]!!.decodeToString(),
            "jarmod entry must win the collision")
        assertEquals("mod", out["mod/Mod.class"]!!.decodeToString())
        assertEquals("replacement", out["b/B.class"]!!.decodeToString(),
            "_-prefixed class must land under the bare name")
        assertFalse(out.containsKey("_b/B.class"))
        assertEquals("base-shared", out["shared.txt"]!!.decodeToString())
    }

    @Test
    fun `jarmod merge drops all mod META-INF and base signature debris`() {
        val mod = modJar(); val base = baseJar()
        val p = JarModMergeProcessor(jarMod = listOf(mod))
        p.processJar(base, p.buildSpec(specCtx())!!, ctx)
        val out = entries(base)
        // the jarmod's whole META-INF is signature-era debris —
        // even load-bearing-looking names don't merge
        assertFalse(out.containsKey("META-INF/mods.toml"))
        // base signature material is always stripped
        assertFalse(out.containsKey("META-INF/MANIFEST.MF"))
        // but non-signature base META-INF survives by default
        assertEquals("keep", out["META-INF/keep.txt"]!!.decodeToString())
    }

    @Test
    fun `deleteMetaInf strips every base META-INF entry`() {
        val mod = modJar(); val base = baseJar()
        val p = JarModMergeProcessor(
            jarMod = listOf(mod), deleteMetaInf = { true })
        p.processJar(base, p.buildSpec(specCtx())!!, ctx)
        assertTrue(entries(base).keys.none { it.startsWith("META-INF") })
    }

    @Test
    fun `jarmod merge envArtifacts channel supplies the mod jars`() {
        val mod = modJar(); val base = baseJar()
        val p = JarModMergeProcessor()
        val spec = p.buildSpec(
            specCtx(mapOf("jarmod:merge" to listOf(mod))))!!
        p.processJar(base, spec, ctx)
        assertEquals("mod", entries(base)["mod/Mod.class"]!!.decodeToString())
    }

    @Test
    fun `jarmod merge no-ops without mod jars`() {
        val p = JarModMergeProcessor()
        assertNull(p.buildSpec(specCtx()))
    }

    @Test
    fun `jarmod merge spec round-trips through serialization`() {
        val mod = modJar(); val base = baseJar()
        val p = JarModMergeProcessor(jarMod = listOf(mod))
        val spec = p.buildSpec(specCtx())!!
        val revived = p.deserializeSpec(p.serializeSpec(spec))
        p.processJar(base, revived, ctx)
        assertEquals("mod", entries(base)["mod/Mod.class"]!!.decodeToString())
    }

    // ---------- FG1FixForgeProcessor ----------

    /** `CoreFMLLibraries.<clinit>` storing [libs] into `libraries`. */
    private fun coreFmlLibraries(libs: List<String>): ByteArray {
        val cn = shellClass("cpw/mods/fml/relauncher/CoreFMLLibraries")
        cn.fields.add(FieldNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "libraries", "[Ljava/lang/String;", null, null))
        cn.methods.add(MethodNode(Opcodes.ACC_STATIC, "<clinit>",
            "()V", null, null).apply {
            instructions.add(TypeInsnNode(Opcodes.ANEWARRAY,
                "java/lang/String"))
            libs.forEach { instructions.add(LdcInsnNode(it)) }
            instructions.add(FieldInsnNode(Opcodes.PUTSTATIC,
                "cpw/mods/fml/relauncher/CoreFMLLibraries",
                "libraries", "[Ljava/lang/String;"))
            instructions.add(InsnNode(Opcodes.RETURN))
        })
        return writeClass(cn)
    }

    private fun fg1Jar(libs: List<String>) = jar("mc.jar", mapOf(
        "cpw/mods/fml/relauncher/CoreFMLLibraries.class" to
                coreFmlLibraries(libs),
        "argo/x.class" to "argo".toByteArray(),
        "org/bouncycastle/y.class" to "bc".toByteArray(),
        "keep/K.class" to "keep".toByteArray()))

    @Test
    fun `fg1-fix-forge strips argo and bouncycastle when dyn libs mention them`() {
        val target = fg1Jar(listOf("argo-2.25.jar", "bcprov-jdk15on.jar"))
        FG1FixForgeProcessor().processJar(target,
            FG1FixForgeProcessor().buildSpec(specCtx())!!, ctx)
        val out = entries(target)
        assertFalse(out.keys.any { it.startsWith("argo/") })
        assertFalse(out.keys.any { it.startsWith("org/bouncycastle/") })
        assertTrue(out.containsKey("keep/K.class"))
        assertTrue(out.containsKey(
            "cpw/mods/fml/relauncher/CoreFMLLibraries.class"))
    }

    @Test
    fun `fg1-fix-forge strips only the mentioned package`() {
        val target = fg1Jar(listOf("argo-2.25.jar", "guava.jar"))
        FG1FixForgeProcessor().processJar(target,
            FG1FixForgeProcessor().buildSpec(specCtx())!!, ctx)
        val out = entries(target)
        assertFalse(out.keys.any { it.startsWith("argo/") })
        assertTrue(out.containsKey("org/bouncycastle/y.class"),
            "bcprov absent from dyn libs — package must stay")
    }

    @Test
    fun `fg1-fix-forge leaves the jar when no marker libs exist`() {
        val target = fg1Jar(listOf("guava.jar"))
        val before = entries(target)
        FG1FixForgeProcessor().processJar(target,
            FG1FixForgeProcessor().buildSpec(specCtx())!!, ctx)
        assertEquals(before.keys, entries(target).keys)
        assertTrue(entries(target).containsKey("argo/x.class"))
    }

    @Test
    fun `fg1-fix-forge ignores arrays stored into other fields`() {
        // clinit: argo array into `other`, guava into `libraries` —
        // the parser clears on non-`libraries` PUTSTATIC so `argo`
        // from the first array must not count
        val cn = shellClass("cpw/mods/fml/relauncher/CoreFMLLibraries")
        cn.methods.add(MethodNode(Opcodes.ACC_STATIC, "<clinit>",
            "()V", null, null).apply {
            instructions.add(TypeInsnNode(Opcodes.ANEWARRAY,
                "java/lang/String"))
            instructions.add(LdcInsnNode("argo-bad.jar"))
            instructions.add(FieldInsnNode(Opcodes.PUTSTATIC,
                "cpw/mods/fml/relauncher/CoreFMLLibraries",
                "other", "[Ljava/lang/String;"))
            instructions.add(TypeInsnNode(Opcodes.ANEWARRAY,
                "java/lang/String"))
            instructions.add(LdcInsnNode("guava.jar"))
            instructions.add(FieldInsnNode(Opcodes.PUTSTATIC,
                "cpw/mods/fml/relauncher/CoreFMLLibraries",
                "libraries", "[Ljava/lang/String;"))
            instructions.add(InsnNode(Opcodes.RETURN))
        })
        val target = jar("mc.jar", mapOf(
            "cpw/mods/fml/relauncher/CoreFMLLibraries.class" to
                    writeClass(cn),
            "argo/x.class" to "argo".toByteArray()))
        FG1FixForgeProcessor().processJar(target,
            FG1FixForgeProcessor().buildSpec(specCtx())!!, ctx)
        assertTrue(entries(target).containsKey("argo/x.class"))
    }

    @Test
    fun `fg1-fix-forge disabled and absent-class paths no-op`() {
        val noFix = jar("mc.jar",
            mapOf("keep/K.class" to "k".toByteArray()))
        FG1FixForgeProcessor().processJar(noFix,
            FG1FixForgeProcessor().buildSpec(specCtx())!!, ctx)
        assertEquals(setOf("keep/K.class"), entries(noFix).keys)

        val disabled = fg1Jar(listOf("argo-x.jar"))
        FG1FixForgeProcessor().processJar(disabled,
            FG1FixForgeProcessor.Spec(false), ctx)
        assertTrue(entries(disabled).containsKey("argo/x.class"))

        // spec payload is just the enablement flag
        val p = FG1FixForgeProcessor()
        assertEquals("true", p.serializeSpec(
            p.deserializeSpec("true")))
    }

}
