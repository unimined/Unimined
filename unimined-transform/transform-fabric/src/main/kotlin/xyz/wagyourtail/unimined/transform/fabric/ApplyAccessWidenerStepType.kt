package xyz.wagyourtail.unimined.transform.fabric

import kotlin.reflect.KClass
import org.gradle.api.GradleException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import xyz.wagyourtail.unimined.transform.fabric.internal.AccessWidener
import xyz.wagyourtail.unimined.transform.fabric.internal.TreeNamespaceRemapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * `apply-access-widener` implementation backed by ASM. For every declared
 * AW file it rebases the directives onto the pipeline's target namespace
 * and adjusts the access flags of matching classes, methods, and fields.
 */
class ApplyAccessWidenerStepType : RemapStepType<ApplyAccessWidenerStepSpec> {

    override val name = "fabric"
    override val stepName = "apply-access-widener"
    override val specType: KClass<ApplyAccessWidenerStepSpec> = ApplyAccessWidenerStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.JAR)

    override val needsMappingTree = true

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: ApplyAccessWidenerStepSpec, ctx: StepContext) = ArtifactStep { input, c ->
        val files = spec.accessWidenerFiles.files.filter { it.isFile }
        if (files.isEmpty()) return@ArtifactStep input

        val tree = c.mappings?.view<MappingTree>()
            ?: throw GradleException(
                "apply-access-widener requires mapping data exposing a MappingTree view"
            )

        val wideners = files.map { AccessWidener.parse(it.readText()) }
        val output = Files.createTempDirectory(c.workDir, "aw")
            .resolve(input.fileName.toString())
        apply(input, output, wideners, tree, c.spec.targetNamespace)
        output
    }

    internal fun apply(
        input: Path,
        output: Path,
        wideners: List<AccessWidener>,
        tree: MappingTree,
        targetNamespace: String
    ) {
        // Rebase each file's directives onto the target namespace so
        // bytecode members can be looked up under their own names.
        val translated = wideners.map { widener ->
            if (widener.namespace == targetNamespace) widener
            else widener.remap(TreeNamespaceRemapper(tree, widener.namespace, targetNamespace))
        }

        JarFile(input.toFile()).use { jar ->
            JarOutputStream(Files.newOutputStream(output)).use { out ->
                for (entry in jar.entries()) {
                    val next = JarEntry(entry.name)
                    next.time = entry.time
                    out.putNextEntry(next)
                    if (!entry.isDirectory) {
                        val bytes = jar.getInputStream(entry).readBytes()
                        out.write(
                            if (entry.name.endsWith(".class")) applyToClass(bytes, translated)
                            else bytes
                        )
                    }
                    out.closeEntry()
                }
            }
        }
    }

    private fun applyToClass(bytes: ByteArray, wideners: List<AccessWidener>): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, 0)
        var className = ""
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {

            override fun visit(
                version: Int, access: Int, name: String,
                signature: String?, superName: String?, interfaces: Array<out String>?
            ) {
                className = name
                super.visit(
                    version, adjust(access) { it.classChange(name) },
                    name, signature, superName, interfaces
                )
            }

            override fun visitMethod(
                access: Int, name: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?
            ): MethodVisitor = super.visitMethod(
                adjust(access) { it.methodChange(className, name, descriptor) },
                name, descriptor, signature, exceptions
            )

            override fun visitField(
                access: Int, name: String, descriptor: String,
                signature: String?, value: Any?
            ): FieldVisitor = super.visitField(
                adjust(access) { it.fieldChange(className, name, descriptor) },
                name, descriptor, signature, value
            )

            private fun adjust(access: Int, change: (AccessWidener) -> AccessWidener.Change?): Int {
                var acc = access
                for (widener in wideners) {
                    change(widener)?.let { acc = (acc and it.clear.inv()) or it.set }
                }
                return acc
            }

        }, 0)
        return writer.toByteArray()
    }

}
