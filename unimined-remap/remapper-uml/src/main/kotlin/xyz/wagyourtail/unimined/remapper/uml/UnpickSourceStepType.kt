package xyz.wagyourtail.unimined.remapper.uml

import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.api.mapping.UmlMappingTreeView
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableConstantGroupMapping
import xyz.wagyourtail.unimined.mapping.formats.unpick.UnpickReader
import xyz.wagyourtail.unimined.mapping.jvms.ext.constant.Constant
import xyz.wagyourtail.unimined.mapping.jvms.ext.expression.Expression
import xyz.wagyourtail.unimined.mapping.remapper.source.SourceOptions
import xyz.wagyourtail.unimined.mapping.remapper.source.SourceRemapper
import xyz.wagyourtail.unimined.mapping.remapper.source.psi.ConstantGroupSourceExtension
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.exists

/**
 * The `unpick` step for [RemapSurface.SOURCES] — replaces inlined
 * constant literals in generated sources with the mapping tree's
 * `ConstantGroupMapping` expressions via UML's
 * [ConstantGroupSourceExtension].
 *
 * Extra definition files declared on [UnpickSourceStepSpec.defs] are read
 * through [UnpickReader] and their constant groups merged into the
 * step's mapping tree — the task/transform path rebuilds mapping data
 * per execution, so the merge never leaks into the resolved environment
 * data.
 */
class UnpickSourceStepType : RemapStepType<UnpickSourceStepSpec> {

    override val name = "uml"
    override val stepName = "unpick"
    override val specType: KClass<UnpickSourceStepSpec> = UnpickSourceStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.SOURCES)

    override val needsMappingTree = true
    override val needsClasspath = true

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: UnpickSourceStepSpec, ctx: StepContext): ArtifactStep =
        ArtifactStep { input, c -> unpick(input, spec, c) }

    private fun unpick(input: Path, spec: UnpickSourceStepSpec, ctx: StepContext): Path {
        val view = ctx.mappings?.view<UmlMappingTreeView>()
            ?: throw GradleException(
                "unpick step 'uml' requires mapping data exposing a UmlMappingTreeView"
            )
        val tree = view.tree as? MutableMappingTree
        val defs = spec.defs.files
        if (defs.isNotEmpty()) {
            if (tree == null) throw GradleException(
                "unpick step 'uml' with defs requires a mutable UML mapping tree"
            )
            runBlocking {
                for (file in defs) {
                    mergeDefs(file.toPath(), tree, ctx.spec.sourceNamespace)
                }
            }
        }
        if (tree != null) {
            materializeExpressions(
                tree, Namespace(ctx.spec.sourceNamespace), ctx.classpath, ctx.logger
            )
        }

        val runtimeSpec = ctx.spec
        val target = Files.createTempDirectory(ctx.workDir, "unpick")
            .resolve(input.fileName.toString())

        ctx.logger.info(
            "[remap/unpick:sources] unpicking constants in {}",
            input.fileName
        )

        SourceRemapper.remap(
            input,
            target,
            view.tree,
            Namespace(runtimeSpec.sourceNamespace),
            Namespace(runtimeSpec.targetNamespace),
            classpath = ctx.classpath.filter { it.exists() },
            options = SourceOptions(extensions = listOf(ConstantGroupSourceExtension()))
        )
        return target
    }

    /**
     * Fills `ExpressionElement`s for groups that only carry constant
     * declarations (v2-style unpick defs) — the literal value lives in the
     * bytecode's `ConstantValue` attribute, mirroring `AsmRemapper`'s
     * `fieldValues` pre-pass but sourced from the classpath jars since the
     * input here is a *sources* jar.
     */
    private fun materializeExpressions(
        tree: MutableMappingTree,
        fns: Namespace,
        classpath: List<Path>,
        logger: Logger,
    ) {
        val groups = tree.constantGroups.filter {
            it.baseNs == fns && it.constants.any()
        }
        if (groups.isEmpty()) return

        val fieldValues = mutableMapOf<String, Any>()
        for (jar in classpath.filter { it.exists() && it.toString().endsWith(".jar") }) {
            try {
                ZipFile(jar.toFile()).use { zip ->
                    for (entry in zip.entries().asSequence().filter {
                        it.name.endsWith(".class") && !it.name.startsWith("META-INF/")
                    }) {
                        try {
                            val node = ClassNode()
                            ClassReader(zip.getInputStream(entry)).accept(
                                node,
                                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG
                                    or ClassReader.SKIP_FRAMES
                            )
                            for (field in node.fields) {
                                if (field.value != null
                                    && field.access and Opcodes.ACC_STATIC != 0
                                ) {
                                    fieldValues.putIfAbsent(
                                        "${node.name}.${field.name}", field.value
                                    )
                                }
                            }
                        } catch (_: Exception) {
                            // skip unreadable class entries
                        }
                    }
                }
            } catch (e: Exception) {
                logger.debug("[remap/unpick] skipping unreadable jar {}", jar)
            }
        }
        if (fieldValues.isEmpty()) return

        var added = 0
        for (group in groups) {
            val mutable = group as? MutableConstantGroupMapping ?: continue
            val existing = group.expressions.map { it.value.value }.toMutableSet()
            for (const in group.constants) {
                val (fieldName) = const.field.getParts()
                val value = fieldValues["${const.owner.value}.${fieldName.value}"]
                    ?: continue
                val literal = if (value is String) "\"$value\"" else value.toString()
                if (!existing.add(literal)) continue
                mutable.expression(
                    Constant.unchecked(literal),
                    Expression.unchecked("this.${fieldName.value}")
                )
                added++
            }
        }
        if (added > 0) {
            logger.info(
                "[remap/unpick] materialized {} constant literal expressions",
                added
            )
        }
    }

    /**
     * Reads one defs file and pushes its constant groups into [tree],
     * rebased onto [sourceNamespace] — the unpick format's own
     * namespace is a placeholder; the constants are written in the
     * namespace of the sources being processed.
     */
    private suspend fun mergeDefs(path: Path, tree: MutableMappingTree, sourceNamespace: String) {
        val base = Namespace(sourceNamespace)
        val defs = UnpickReader.read(path.source().buffer())
        for (group in defs.constantGroups) {
            val merged = tree.constantGroup(group.type, group.name, base)
            group.metadata.forEach(merged::addMetadata)
        }
    }

}
