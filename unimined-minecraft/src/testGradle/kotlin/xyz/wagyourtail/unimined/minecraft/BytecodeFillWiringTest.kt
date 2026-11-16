package xyz.wagyourtail.unimined.minecraft

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.jvms.four.two.two.UnqualifiedName
import xyz.wagyourtail.unimined.minecraft.internal.declaresOrnitheEnrichers
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * `hierarchyNameFill`/Ornithe-enricher gating — the bytecode hierarchy fill
 * registers as a spec-scoped `bytecode-fill` post-processor inside
 * mapping resolution. The processor's jar scan degrades to a no-op on
 * envs without a resolvable product, so the versionless main env still
 * resolves.
 */
class BytecodeFillWiringTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also {
            // registers JavaToolchainService — real consumers apply java
            // themselves; the plugin does not
            it.plugins.apply("java")
            it.plugins.apply(GradlePlugin::class.java)
        }

    @Test
    fun `hierarchyNameFill opt-in registers the fill post processor`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.mappings { it.hierarchyNameFill.set(true) }
        ext.mappingData().get()
        assertTrue(
            ext.sourceSet("main").mappingsSpec.postProcessors
                .any { it.name == "bytecode-fill" },
            "hierarchyNameFill = true must append the bytecode-fill post processor"
        )
    }

    /** D2 — a provider-backed `hierarchyNameFill` stays unevaluated
     *  through configuration; the flag is read inside mapping-data
     *  materialization, so `mappingData()` is the first resolution. */
    @Test
    fun `hierarchyNameFill provider resolves inside mapping materialization`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        var evaluations = 0
        ext.mappings {
            it.hierarchyNameFill.set(
                project.provider { evaluations++; true })
        }
        assertEquals(0, evaluations,
            "hierarchyNameFill provider must not resolve during configuration")
        ext.mappingData().get()
        assertEquals(1, evaluations)
        assertTrue(
            ext.sourceSet("main").mappingsSpec.postProcessors
                .any { it.name == "bytecode-fill" },
            "provider-backed hierarchyNameFill must still gate the " +
                    "bytecode-fill post processor"
        )
    }

    @Test
    fun `hierarchyNameFill opt-out leaves the spec without the fill post processor`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.mappings {
            it.official()
            it.hierarchyNameFill.set(false)
        }
        ext.mappingData().get()
        assertFalse(
            ext.sourceSet("main").mappingsSpec.postProcessors
                .any { it.name == "bytecode-fill" }
        )
    }

    @Test
    fun `declaresOrnitheEnrichers detects enricher maven coordinates`() {
        val project = project()
        val spec = MappingsSpecImpl(
            project.objects,
            project.providers.provider { "b1.7.3" },
            project.providers.provider { Side.CLIENT }
        )
        spec.nests(1)
        assertTrue(spec.declaresOrnitheEnrichers())
    }

    @Test
    fun `declaresOrnitheEnrichers ignores ordinary maven sources`() {
        val project = project()
        val spec = MappingsSpecImpl(project.objects)
        spec.maven("net.fabricmc:yarn:1.2.3+build.4:v2")
        assertFalse(spec.declaresOrnitheEnrichers())
    }

    // ---- end-to-end fill against the env's product jar ----

    private fun writeClass(
        name: String,
        superName: String = "java/lang/Object",
        vararg methods: Pair<String, String>
    ): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, superName, null)
        for ((mname, mdesc) in methods) {
            val mv: MethodVisitor =
                cw.visitMethod(Opcodes.ACC_PUBLIC, mname, mdesc, null, null)
            mv.visitCode()
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(-1, -1)
            mv.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** `a/Base -> a/Sub` redeclaring `m(I)V` — written to the fake
     *  provider's merged-artifact path (`build/fake/merged.jar`) so
     *  the fill's jar scan sees a real hierarchy. */
    private fun writeFakeJar() {
        val jar = dir.resolve("build/fake/merged.jar")
        Files.createDirectories(jar.parent)
        JarOutputStream(Files.newOutputStream(jar)).use { jos ->
            for ((name, bytes) in sortedMapOf(
                "a/Base" to writeClass("a/Base", methods = arrayOf("m" to "(I)V")),
                "a/Sub" to writeClass("a/Sub", "a/Base", "m" to "(I)V"),
            )) {
                jos.putNextEntry(JarEntry("$name.class"))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
    }

    /** `a/Base.m` mapped to `run`, `a/Sub.m` left unmapped. */
    private fun writeMappings(): Path {
        val file = dir.resolve("mappings.tiny")
        Files.writeString(
            file,
            "tiny\t2\t0\tofficial\tnamed\n" +
                "c\ta/Base\tb/Base\n" +
                "\tm\t(I)V\tm\trun\n" +
                "c\ta/Sub\tb/Sub\n" +
                "\tm\t(I)V\tm\n"
        )
        return file
    }

    private fun namedCell(tree: MappingTree): String? {
        val official = Namespace("official")
        val named = Namespace("named")
        val sub = tree.getClass(official, InternalName.unchecked("a/Sub"))!!
        return with(tree) {
            sub.getMethod(
                official, UnqualifiedName.unchecked("m"),
                MethodDescriptor.unchecked("(I)V")
            )
        }!!.names[named]?.value
    }

    @Test
    fun `hierarchyNameFill propagates method names against the environment jar`() {
        writeFakeJar()
        val mappingFile = writeMappings()
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("1.0")
        ext.provider.set("fake")
        ext.mappings { spec ->
            spec.file(mappingFile)
            spec.hierarchyNameFill.set(true)
        }

        val tree = ext.mappingData().get().view<MappingTree>()!!
        assertEquals("run", namedCell(tree))
    }

    @Test
    fun `hierarchyNameFill unset leaves the composer identity fill`() {
        writeFakeJar()
        val mappingFile = writeMappings()
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("1.0")
        ext.provider.set("fake")
        ext.mappings { spec -> spec.file(mappingFile) }

        val tree = ext.mappingData().get().view<MappingTree>()!!
        // the composer's fillMissingNames copies the src cell into
        // the empty named cell — the bytecode fill is what would turn
        // the identity "m" into the propagated "run"
        assertEquals("m", namedCell(tree))
    }

}
