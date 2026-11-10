package xyz.wagyourtail.unimined.remapper.uml

import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.commonskt.reader.StringCharReader
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.FormatReader
import xyz.wagyourtail.unimined.mapping.formats.feather.ExceptionReader
import xyz.wagyourtail.unimined.mapping.formats.feather.NestReader
import xyz.wagyourtail.unimined.mapping.formats.feather.SignatureReader
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.remapper.asm.MetadataAdapter
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.reflect.KClass

/**
 * Shared engine for the feather-extra steps (`nester`/`raven`/`sparrow`):
 * parse the declared file(s) into a staging [MutableMappingTree] through
 * the format's [FormatReader], then apply each entry's metadata to the
 * matching class via [MetadataAdapter] — the same application path the
 * enricher route uses during a real remap.
 *
 * The file's `source` namespace is bound to the artifact's live
 * namespace ([spec]'s `namespace`, defaulting to the pipeline's
 * `currentNamespace`), so names line up with the jar on disk and
 * [MetadataAdapter]'s namespace-aware signature mapping is an identity.
 */
abstract class FeatherMetadataStepType<S : FeatherMetadataStepSpec>(
    override val specType: KClass<S>,
    override val stepName: String,
    private val reader: FormatReader,
) : RemapStepType<S> {

    override val name = "ornithe-$stepName"

    override val surfaces = setOf(RemapSurface.JAR, RemapSurface.DECOMPILE)

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: S, ctx: StepContext) = ArtifactStep { input, sctx ->
        val ns = Namespace(spec.namespace.orNull ?: sctx.state.currentNamespace)
        val tree = MutableMappingTree()
        for (file in spec.dataFiles.files) {
            runBlocking {
                reader.readImpl(
                    StringCharReader(file.readText().replace("\r", "")),
                    null,
                    tree,
                    mapOf("source" to ns.name),
                    reader
                )
            }
        }
        val out = sctx.workDir.resolve(
            input.fileName.toString().removeSuffix(".jar") + "-$stepName.jar"
        )
        JarFile(input.toFile()).use { jar ->
            JarOutputStream(Files.newOutputStream(out)).use { os ->
                for (entry in jar.entries()) {
                    val next = JarEntry(entry.name)
                    next.time = entry.time
                    os.putNextEntry(next)
                    if (!entry.isDirectory) {
                        val bytes = jar.getInputStream(entry).readBytes()
                        os.write(
                            if (entry.name.endsWith(".class")) applyMeta(bytes, tree, ns)
                            else bytes
                        )
                    }
                    os.closeEntry()
                }
            }
        }
        out
    }

    private fun applyMeta(
        bytes: ByteArray, tree: MutableMappingTree, ns: Namespace
    ): ByteArray {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        val cls = tree.getClass(ns, InternalName.read(node.name)) ?: return bytes
        MetadataAdapter.apply(node, cls, tree, ns)
        val writer = ClassWriter(0)
        node.accept(writer)
        return writer.toByteArray()
    }
}

/** `nester` — `.nests` → InnerClasses attributes. */
class NesterStepType : FeatherMetadataStepType<NesterStepSpec>(
    NesterStepSpec::class, "nester", NestReader
)

/** `raven` — `.excs` → method `Exceptions` attributes. */
class RavenStepType : FeatherMetadataStepType<RavenStepSpec>(
    RavenStepSpec::class, "raven", ExceptionReader
)

/** `sparrow` — `.sigs` → `Signature` attributes. */
class SparrowStepType : FeatherMetadataStepType<SparrowStepSpec>(
    SparrowStepSpec::class, "sparrow", SignatureReader
)
