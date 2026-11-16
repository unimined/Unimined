package xyz.wagyourtail.unimined.adapter.expectplatform

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import xyz.wagyourtail.commons.core.logger.JavaLogWrapper
import xyz.wagyourtail.commons.core.logger.Logger
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import kotlin.io.path.outputStream

/**
 * `expectPlatform` step execution — the [ExpectPlatformStepType]
 * ArtifactStep driving ExpectPlatform's `TransformPlatform` over a
 * jar: `@ExpectPlatform` body rewrite, `@PlatformOnly` filtering,
 * the `remap` annotation-desc table, and `stripAnnotations`.
 * Fixture classes are ASM-emitted (invisible annotations are just
 * descriptor strings — the real annotation artifact stays off the
 * test classpath).
 */
class ExpectPlatformStepTypeTest {

    @TempDir
    lateinit var dir: Path

    private val objects = ProjectBuilder.builder().build().objects
    private val type = ExpectPlatformStepType()

    private val ctx: StepContext
        get() = object : StepContext {
            override val surface = RemapSurface.JAR
            override val state: PipelineState by lazy {
                PipelineState(spec.sourceNamespace, null)
            }
            override val spec = object : RemapSpec {
                override val sourceNamespace = "official"
                override val targetNamespace = "named"
                override val classpath: List<Path> = emptyList()
            }
            override val mappings: MappingData? = null
            override val classpath = emptyList<Path>()
            // createTempDirectory(workDir) in the step requires the
            // parent to exist — a real pipeline's workDir is wired
            // ahead of the steps
            override val workDir = dir.resolve("work")
                .also { Files.createDirectories(it) }
            override val logger: Logger = JavaLogWrapper(
                java.util.logging.Logger.getLogger("expectPlatform-test"))
        }

    private fun spec(
        platform: String = "fabric",
        remap: Map<String, String> = emptyMap(),
        strip: Boolean? = null
    ) = objects.newInstance(ExpectPlatformStepSpec::class.java).apply {
        platformName.set(platform)
        this.remap.set(remap)
        strip?.let { stripAnnotations.set(it) }
    }

    private fun jar(name: String, entries: Map<String, ByteArray>): Path {
        val out = dir.resolve(name)
        JarOutputStream(out.outputStream()).use { jos ->
            for ((entryName, bytes) in entries) {
                jos.putNextEntry(JarEntry(entryName))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
        return out
    }

    private fun classNode(jar: Path, internal: String): ClassNode =
        ZipFile(jar.toFile()).use { z ->
            ClassNode().also {
                ClassReader(z.getInputStream(z.getEntry("$internal.class")))
                    .accept(it, 0)
            }
        }

    private fun entryBytes(jar: Path, name: String): ByteArray =
        ZipFile(jar.toFile()).use { z ->
            z.getInputStream(z.getEntry(name)).readBytes()
        }

    private fun MethodNode.staticCalls(): List<MethodInsnNode> =
        instructions.toArray().filterIsInstance<MethodInsnNode>()
            .filter { it.opcode == Opcodes.INVOKESTATIC }

    private fun epAnn(vararg platforms: AnnotationNode) = AnnotationNode(
        "Lxyz/wagyourtail/unimined/expect/annotation/ExpectPlatform;")
        .also { ann ->
            if (platforms.isNotEmpty()) {
                ann.visit("platforms", platforms.toList())
            }
        }

    private fun platformAnn(name: String, target: String) = AnnotationNode(
        "Lxyz/wagyourtail/unimined/expect/annotation/Platform;")
        .also { it.visit("name", name); it.visit("target", target) }

    private fun platformOnlyAnn(vararg names: String) = AnnotationNode(
        "Lxyz/wagyourtail/unimined/expect/annotation/PlatformOnly;")
        .also { it.visit("value", names.toList()) }

    private fun environmentAnn() = AnnotationNode(
        "Lxyz/wagyourtail/unimined/expect/annotation/Environment;")
        .also { it.visitEnum("value",
            "Lxyz/wagyourtail/unimined/expect/annotation/Environment\$EnvType;",
            "CLIENT") }

    private fun stubMethod(
        access: Int, name: String, desc: String,
        vararg anns: AnnotationNode
    ) = MethodNode(access, name, desc, null, null).also { m ->
        m.instructions.add(InsnNode(Opcodes.ACONST_NULL))
        m.instructions.add(InsnNode(Opcodes.ARETURN))
        if (anns.isNotEmpty()) {
            m.invisibleAnnotations = anns.toList()
        }
    }

    private fun holderClass(): ByteArray {
        val cn = ClassNode()
        cn.version = Opcodes.V17
        cn.access = Opcodes.ACC_PUBLIC
        cn.name = "com/example/EpHolder"
        cn.superName = "java/lang/Object"
        cn.invisibleAnnotations = listOf(environmentAnn())
        cn.methods.add(stubMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "platform", "()Ljava/lang/String;", epAnn()))
        cn.methods.add(stubMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "explicit", "()Ljava/lang/String;",
            epAnn(platformAnn("fabric", "custom/Target"))))
        cn.methods.add(stubMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "fabricOnly", "()Ljava/lang/String;",
            platformOnlyAnn("fabric")))
        cn.methods.add(stubMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "forgeOnly", "()Ljava/lang/String;",
            platformOnlyAnn("forge")))
        val cw = ClassWriter(0)
        cn.accept(cw)
        return cw.toByteArray()
    }

    private fun inputJar() = jar("mod.jar", mapOf(
        "com/example/EpHolder.class" to holderClass(),
        "assets/data.txt" to "keep-me".toByteArray()))

    @Test
    fun `expectPlatform methods rewrite to the platform impl call`() {
        val out = type.create(spec(), ctx).apply(inputJar(), ctx)
        val cn = classNode(out, "com/example/EpHolder")
        val platform = cn.methods.single { it.name == "platform" }
        val call = platform.staticCalls().single()
        // default impl resolution: <pkg>/<platform>/<Class>Impl
        assertEquals("com/example/fabric/EpHolderImpl", call.owner)
        assertEquals("platform", call.name)
        assertEquals("()Ljava/lang/String;", call.desc)
    }

    @Test
    fun `explicit platform target wins over the derived impl`() {
        val out = type.create(spec(), ctx).apply(inputJar(), ctx)
        val cn = classNode(out, "com/example/EpHolder")
        val explicit = cn.methods.single { it.name == "explicit" }
        assertEquals("custom/Target", explicit.staticCalls().single().owner)
    }

    @Test
    fun `platformOnly drops methods for other platforms`() {
        val out = type.create(spec(), ctx).apply(inputJar(), ctx)
        val cn = classNode(out, "com/example/EpHolder")
        assertNotNull(cn.methods.find { it.name == "fabricOnly" },
            "same-platform method should be kept")
        assertNull(cn.methods.find { it.name == "forgeOnly" },
            "foreign-platform method should be dropped")
    }

    @Test
    fun `stripAnnotations removes ExpectPlatform annotations`() {
        val stripped = type.create(spec(strip = true), ctx)
            .apply(inputJar(), ctx)
        val cn = classNode(stripped, "com/example/EpHolder")
        val platform = cn.methods.single { it.name == "platform" }
        assertTrue(
            platform.invisibleAnnotations.orEmpty().none {
                it.desc.contains("expect/annotation/ExpectPlatform")
            }, "stripAnnotations must drop the marker annotation")

        val kept = type.create(spec(strip = false), ctx)
            .apply(inputJar(), ctx)
        val keptCn = classNode(kept, "com/example/EpHolder")
        val keptMethod = keptCn.methods.single { it.name == "platform" }
        assertTrue(
            keptMethod.invisibleAnnotations.orEmpty().any {
                it.desc.contains("expect/annotation/ExpectPlatform")
            }, "default keeps the marker annotation")
    }

    @Test
    fun `remap rewrites annotation descriptors`() {
        val out = type.create(spec(remap = mapOf(
            "xyz/wagyourtail/unimined/expect/annotation/Environment" to
                    "net/fabricmc/api/Environment",
            "xyz/wagyourtail/unimined/expect/annotation/Environment\$EnvType" to
                    "net/fabricmc/api/EnvType")), ctx)
            .apply(inputJar(), ctx)
        val cn = classNode(out, "com/example/EpHolder")
        val anns = cn.invisibleAnnotations.orEmpty()
        assertTrue(anns.any { it.desc == "Lnet/fabricmc/api/Environment;" },
            "Environment annotation should remap: $anns")
        // enum-typed members ride the same table
        val env = anns.single { it.desc == "Lnet/fabricmc/api/Environment;" }
        val enumDesc = env.values.run {
            val i = indexOf("value")
            (get(i + 1) as Array<*>)[0] as String
        }
        assertEquals("Lnet/fabricmc/api/EnvType;", enumDesc)
    }

    @Test
    fun `non-class entries pass through untouched`() {
        val out = type.create(spec(), ctx).apply(inputJar(), ctx)
        assertEquals("keep-me",
            entryBytes(out, "assets/data.txt").toString(Charsets.UTF_8))
        assertTrue(Files.exists(out))
    }

}
