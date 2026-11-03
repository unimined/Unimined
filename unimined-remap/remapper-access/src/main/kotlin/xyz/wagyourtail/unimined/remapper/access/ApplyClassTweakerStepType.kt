package xyz.wagyourtail.unimined.remapper.access

import org.gradle.api.GradleException
import org.objectweb.asm.*
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.element.metadata.AccessElement
import xyz.wagyourtail.unimined.mapping.jvms.four.AccessFlag
import xyz.wagyourtail.unimined.mapping.jvms.four.ElementType
import xyz.wagyourtail.unimined.mapping.remapper
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.reflect.KClass

/**
 * `apply-class-tweaker` implementation backed by ASM. For every declared
 * AW/CT/AT file it rebases the directives onto the pipeline's target
 * namespace and adjusts the access flags of matching classes, methods,
 * and fields through UML's conditional [AccessElement] model.
 */
class ApplyClassTweakerStepType : RemapStepType<ApplyClassTweakerStepSpec> {

    override val name = "access"
    override val stepName = "apply-class-tweaker"
    override val specType: KClass<ApplyClassTweakerStepSpec> = ApplyClassTweakerStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.JAR)

    override val needsMappingTree = true

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: ApplyClassTweakerStepSpec, ctx: StepContext) = ArtifactStep { input, c ->
        val files = spec.classTweakerFiles.files.filter { it.isFile }
        val depFiles = spec.dependencyClassTweakerFiles.files.filter { it.isFile }
        if (files.isEmpty() && depFiles.isEmpty()) return@ArtifactStep input

        val tree = c.mappings?.view<MappingTree>()
            ?: throw GradleException(
                "apply-class-tweaker requires mapping data exposing a MappingTree view"
            )

        // `source` = the anonymous namespace of headerless AT files —
        // the spec binds it to the ns the file's names are actually in
        val nsMapping = spec.headerlessNamespace.orNull
            ?.let { mapOf("source" to it) } ?: emptyMap()
        val tweakers = files.map { ClassTweaker.parse(it.name, it.readText(), nsMapping) }
        val depTweakers = depFiles.map { ClassTweaker.parse(it.name, it.readText(), nsMapping) }
        val output = Files.createTempDirectory(c.workDir, "ct")
            .resolve(input.fileName.toString())
        apply(input, output, tweakers, depTweakers, tree, c.spec.targetNamespace)
        output
    }

    internal fun apply(
        input: Path,
        output: Path,
        tweakers: List<ClassTweaker>,
        dependencyTweakers: List<ClassTweaker>,
        tree: MappingTree,
        targetNamespace: String
    ) {
        // Rebase each file's directives onto the target namespace so
        // bytecode members can be looked up under their own names.
        fun translate(tweaker: ClassTweaker) =
            if (tweaker.namespace == targetNamespace) tweaker
            else {
                val src = tree.namespaces.firstOrNull { it.name == tweaker.namespace }
                    ?: throw GradleException(
                        "apply-class-tweaker: file namespace '${tweaker.namespace}' " +
                                "is not in the mapping tree"
                    )
                val dst = tree.namespaces.firstOrNull { it.name == targetNamespace }
                    ?: throw GradleException(
                        "apply-class-tweaker: target namespace '$targetNamespace' " +
                                "is not in the mapping tree"
                    )
                tweaker.remap(tree.remapper(src, dst))
            }

        val translated = tweakers.map(::translate)
        val depTranslated = dependencyTweakers.map(::translate)

        JarFile(input.toFile()).use { jar ->
            JarOutputStream(Files.newOutputStream(output)).use { out ->
                for (entry in jar.entries()) {
                    val next = JarEntry(entry.name)
                    next.time = entry.time
                    out.putNextEntry(next)
                    if (!entry.isDirectory) {
                        val bytes = jar.getInputStream(entry).readBytes()
                        out.write(
                            if (entry.name.endsWith(".class")) {
                                applyToClass(bytes, translated, depTranslated)
                            } else bytes
                        )
                    }
                    out.closeEntry()
                }
            }
        }
    }

    /** `inject-interface` signature → the internal name for the
     *  `interfaces` array (`Lfoo/Bar<TT;>;` or bare `foo/Bar<TT;>`). */
    private fun interfaceInternal(signature: String): String =
        signature.removePrefix("L")
            .substringBefore('<').removeSuffix(";")

    /** Wrap a signature-ish string as a `L...;` class-type-signature for
     *  appending to a class's `Signature` attribute. */
    private fun wrap(signature: String): String =
        (if (signature.startsWith('L')) signature else "L$signature")
            .let { if (it.endsWith(';')) it else "$it;" }

    private fun applyToClass(
        bytes: ByteArray,
        tweakers: List<ClassTweaker>,
        depTweakers: List<ClassTweaker>
    ): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, 0)
        var className = ""
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {

            override fun visit(
                version: Int, access: Int, name: String,
                signature: String?, superName: String?, interfaces: Array<out String>?
            ) {
                className = name
                val injected = injectedSignatures(name)
                val merged = (interfaces?.toList().orEmpty() +
                        injected.map(::interfaceInternal)
                        ).distinct().toTypedArray()
                super.visit(
                    version,
                    adjust(access, ElementType.CLASS) { t, d -> t.classChanges(name, d) },
                    name,
                    injected.fold(signature ?: "") { sig, inj -> sig + wrap(inj) }
                        .takeIf { it.isNotEmpty() },
                    superName, merged
                )
            }

            /**
             * `inject-interface` signatures targeting [name] — own files
             * contribute all directives, dependency files only
             * `transitive-inject-interface` (same partition as access).
             */
            private fun injectedSignatures(name: String): List<String> =
            // transitive directives appear as both (inj,false) — the
                // local role — and (inj,true) — dependency propagation
                tweakers.flatMap { t ->
                    t.interfaces.filter { it.first.owner == name && !it.second }
                        .map { it.first.signature }
                } + depTweakers.flatMap { t ->
                    t.interfaces.filter { it.first.owner == name && it.second }
                        .map { it.first.signature }
                }

            override fun visitMethod(
                access: Int, name: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?
            ): MethodVisitor = super.visitMethod(
                adjust(access, ElementType.METHOD) { t, d -> t.methodChanges(className, name, descriptor, d) },
                name, descriptor, signature, exceptions
            )

            override fun visitField(
                access: Int, name: String, descriptor: String,
                signature: String?, value: Any?
            ): FieldVisitor = super.visitField(
                adjust(access, ElementType.FIELD) { t, d -> t.fieldChanges(className, name, descriptor, d) },
                name, descriptor, signature, value
            )

            private fun adjust(
                access: Int,
                type: ElementType,
                changes: (ClassTweaker, Boolean) -> List<AccessElement>
            ): Int {
                val flags = AccessFlag.of(type, access).toMutableSet()
                for (tweaker in tweakers) {
                    for (element in changes(tweaker, false)) element.apply(flags)
                }
                // dependency tweakers contribute only their transitive-*
                // directives to the shared artifact (TransitiveOnlyFilter)
                for (tweaker in depTweakers) {
                    for (element in changes(tweaker, true)) element.apply(flags)
                }
                return AccessFlag.toInt(flags)
            }

        }, 0)
        return writer.toByteArray()
    }

}
