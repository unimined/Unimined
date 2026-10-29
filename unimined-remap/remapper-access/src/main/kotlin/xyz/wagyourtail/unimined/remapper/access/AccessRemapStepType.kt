package xyz.wagyourtail.unimined.remapper.access

import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.commonskt.reader.StringCharReader
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.at.ATReader
import xyz.wagyourtail.unimined.mapping.formats.at.ATWriter
import xyz.wagyourtail.unimined.mapping.formats.aw.AWReader
import xyz.wagyourtail.unimined.mapping.formats.aw.AWWriter
import xyz.wagyourtail.unimined.mapping.formats.ct.CTReader
import xyz.wagyourtail.unimined.mapping.formats.ct.CTWriter
import xyz.wagyourtail.unimined.remap.AccessRemapStepSpec
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
 * `remap-access` implementation for engines that copy resources verbatim.
 * It rewrites embedded Access Widener, Forge Access Transformer, and
 * ClassTweaker files using the resolved UML [MappingTree].
 */
class AccessRemapStepType : RemapStepType<AccessRemapStepSpec> {

    override val name = "access"
    override val stepName = "remap-access"
    override val specType: KClass<AccessRemapStepSpec> = AccessRemapStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.JAR, RemapSurface.DECOMPILE)
    override val needsMappingTree = true

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: AccessRemapStepSpec, ctx: StepContext) = ArtifactStep { input, c ->
        val tree = c.mappings?.view<MappingTree>()
            ?: throw GradleException("remap-access requires mapping data exposing a MappingTree view")
        val output = Files.createTempDirectory(c.workDir, "access").resolve(input.fileName.toString())
        val formats = spec.formats.map { it.lowercase() }.toSet()
        val unknown = formats - setOf("aw", "at", "ct")
        if (unknown.isNotEmpty()) {
            throw GradleException("remap-access: unknown format(s): ${unknown.joinToString()}")
        }
        rewriteJar(
            input, output, tree,
            Namespace(c.spec.sourceNamespace), Namespace(c.spec.targetNamespace),
            formats, spec.paths
        )
        output
    }

    private enum class Kind { AW, AT, CT }

    private fun rewriteJar(
        input: Path,
        output: Path,
        tree: MappingTree,
        sourceNs: Namespace,
        targetNs: Namespace,
        formats: Set<String>,
        explicitPaths: Set<String>
    ) {
        JarFile(input.toFile()).use { jar ->
            // FMLAT manifest attr — old-FML custom AT paths
            // (space-separated); 1.x AtRemapper parity — per-jar,
            // so merged here rather than on the spec
            val declared = (jar.manifest?.mainAttributes
                ?.getValue("FMLAT") as? String)
                ?.split(" ")?.filter(String::isNotEmpty)?.toSet()
                .orEmpty()
            val explicit = explicitPaths + declared
            JarOutputStream(Files.newOutputStream(output)).use { out ->
                for (entry in jar.entries()) {
                    val next = JarEntry(entry.name)
                    next.time = entry.time
                    out.putNextEntry(next)
                    if (!entry.isDirectory) {
                        val bytes = jar.getInputStream(entry).readBytes()
                        out.write(
                            when (kind(entry.name, bytes, explicit, formats)) {
                                Kind.AW -> remapAw(bytes, tree, targetNs)
                                Kind.AT -> remapAt(bytes, tree, sourceNs, targetNs)
                                Kind.CT -> remapCt(bytes, tree, targetNs)
                                null -> bytes
                            }
                        )
                    }
                    out.closeEntry()
                }
            }
        }
    }

    private fun kind(
        path: String,
        bytes: ByteArray,
        explicitPaths: Set<String>,
        formats: Set<String>
    ): Kind? {
        val base = path.substringAfterLast('/')
        val mayContainText = path in explicitPaths ||
                path.endsWith(".accesswidener", true) ||
                base.endsWith(".cfg", true) ||
                base.endsWith(".ct", true) ||
                base.endsWith(".classtweaker", true)
        val firstContentLine by lazy {
            if (!mayContainText) null else bytes.toString(Charsets.UTF_8).lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        }
        val inferred = when {
            path.endsWith(".accesswidener", true) ||
                    firstContentLine?.startsWith("accessWidener") == true -> Kind.AW

            firstContentLine?.startsWith("classTweaker") == true ||
                    base.endsWith(".ct", true) || base.endsWith(".classtweaker", true) -> Kind.CT

            base.equals("accesstransformer.cfg", true) ||
                    base.endsWith("_at.cfg", true) || path in explicitPaths -> Kind.AT

            else -> null
        }
        return inferred?.takeIf { it.format in formats }
    }

    private val Kind.format: String
        get() = when (this) {
            Kind.AW -> "aw"
            Kind.AT -> "at"
            Kind.CT -> "ct"
        }

    // AW/AT round through UML's format pipeline — `readData` keeps
    // comments, wildcards and desc-less field targets as items,
    // `remapMappings` resolves against the tree, and the writers emit
    // format-correct text (`ATWriter` restores the `.`-separated class
    // names FML's grammar requires)
    private fun remapAw(bytes: ByteArray, tree: MappingTree, targetNs: Namespace): ByteArray {
        val out = StringBuilder()
        AWWriter.writeData(
            AWWriter.remapMappings(
                AWReader.readData(StringCharReader(bytes.toString(Charsets.UTF_8))),
                tree, targetNs
            )
        ) { out.append(it) }
        return out.toString().toByteArray()
    }

    private fun remapAt(
        bytes: ByteArray,
        tree: MappingTree,
        sourceNs: Namespace,
        targetNs: Namespace
    ): ByteArray {
        val out = StringBuilder()
        ATWriter.writeData(
            ATWriter.remapMappings(
                ATReader.readData(StringCharReader(bytes.toString(Charsets.UTF_8)), leinient = true),
                tree, sourceNs, targetNs
            )
        ) { out.append(it) }
        return out.toString().toByteArray()
    }

    private fun remapCt(bytes: ByteArray, tree: MappingTree, targetNs: Namespace): ByteArray {
        val out = StringBuilder()
        CTWriter.writeData(
            CTWriter.remapMappings(
                CTReader.readData(StringCharReader(bytes.toString(Charsets.UTF_8))),
                tree, targetNs
            )
        ) { out.append(it) }
        return out.toString().toByteArray()
    }
}
