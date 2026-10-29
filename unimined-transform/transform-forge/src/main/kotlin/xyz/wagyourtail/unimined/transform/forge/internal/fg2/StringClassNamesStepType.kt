package xyz.wagyourtail.unimined.transform.forge.internal.fg2

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.objectweb.asm.*
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.remap.spi.*
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.reflect.KClass

/**
 * `forge-string-classnames` JAR-surface step — 1.x
 * `StringClassNameRemapExtension` parity. Forge classes such as
 * `ObjectHolderRegistry` hold *class names* in string literals
 * (reflection against MC classes compiled under prod names). A
 * remapper never visits LDC strings, so without this pass those
 * names resolve wrong on any non-prod dev namespace (yarn, mcp,
 * mojmap).
 *
 * Engine-agnostic — a standalone ASM pass after the remap step, so it
 * works under `tiny` and `uml` alike (1.x had to be a tiny-remapper
 * `extraPreApplyVisitor` because the transform lived inside the
 * remap pass).
 */
abstract class StringClassNamesStepSpec : DevArtifactOnlyStepSpec {

    /** Whether the pass applies (profile-dependent). */
    @get:Input
    abstract val enabled: Property<Boolean>

    /** Internal names the pass is scoped to — forge's string-reflection
     *  classes across the eras (ObjectHolderRegistry lives in three
     *  packages: `fml.common.registry` pre-1.13, `registries` on 1.13+,
     *  `neoforge.registries` on neoforge). */
    @get:Input
    abstract val classes: ListProperty<String>
}

class StringClassNamesStepType : RemapStepType<StringClassNamesStepSpec> {

    override val name = "unimined"
    override val stepName = "forge-string-classnames"
    override val specType: KClass<StringClassNamesStepSpec> =
        StringClassNamesStepSpec::class
    override val surfaces = setOf(RemapSurface.JAR)
    override val needsMappingTree = true

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: StringClassNamesStepSpec, ctx: StepContext) =
        ArtifactStep { input, c ->
            if (!spec.enabled.getOrElse(false)) return@ArtifactStep input
            val tree = ctx.mappings?.view<MappingTree>()
                ?: return@ArtifactStep input
            val fromNs = Namespace(c.spec.sourceNamespace)
            val toNs = Namespace(c.spec.targetNamespace)
            val scoped = spec.classes.getOrElse(emptyList()).toSet()
            if (scoped.isEmpty()) return@ArtifactStep input

            val output = Files.createTempDirectory(c.workDir, "str-classnames")
                .resolve(input.fileName.toString())
            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING)
            FileSystems.newFileSystem(output).use { fs ->
                for (name in scoped) {
                    val entry = fs.getPath("$name.class")
                    if (!Files.exists(entry)) continue
                    val reader = ClassReader(Files.readAllBytes(entry))
                    val writer = ClassWriter(0)
                    reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
                        override fun visitMethod(
                            access: Int, mName: String?, desc: String?,
                            sig: String?, ex: Array<out String>?
                        ): MethodVisitor =
                            object : MethodVisitor(
                                Opcodes.ASM9,
                                super.visitMethod(access, mName, desc, sig, ex)
                            ) {
                                override fun visitLdcInsn(value: Any?) {
                                    super.visitLdcInsn(
                                        if (value is String) remapString(
                                            value, tree, fromNs, toNs, c.logger
                                        )
                                        else value
                                    )
                                }
                            }
                    }, 0)
                    Files.write(entry, writer.toByteArray())
                }
            }
            output
        }

    private val classDescRegex = Regex("L([^;]+);")

    /**
     * 1.x semantics: `Ldesc;` literals and bare slash/dotted class
     * names map through the tree; strings mixing `.` and `/` are not
     * class names and stay untouched.
     */
    private fun remapString(
        value: String,
        tree: MappingTree,
        fromNs: Namespace,
        toNs: Namespace,
        logger: org.gradle.api.logging.Logger
    ): String {
        if (classDescRegex.containsMatchIn(value)) {
            return classDescRegex.replace(value) {
                val mapped = mapClassName(tree, it.groupValues[1], fromNs, toNs)
                if (mapped != it.groupValues[1]) {
                    logger.info(
                        "[forge/string-classnames] {} -> {} in a string",
                        it.groupValues[1], mapped
                    )
                    "L$mapped;"
                } else {
                    it.value
                }
            }
        }
        if (value.contains('.') && value.contains('/')) return value
        val dotty = value.contains('.')
        val internal = if (dotty) value.replace('.', '/') else value
        val mapped = mapClassName(tree, internal, fromNs, toNs)
        if (mapped != internal) {
            logger.info(
                "[forge/string-classnames] {} -> {} in a string", value, mapped
            )
            return if (dotty) mapped.replace('/', '.') else mapped
        }
        return value
    }

    private fun mapClassName(
        tree: MappingTree,
        name: String,
        fromNs: Namespace,
        toNs: Namespace
    ): String = tree.getClass(fromNs, InternalName.unchecked(name))
        ?.names?.get(toNs)?.toString() ?: name
}
