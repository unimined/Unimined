package xyz.wagyourtail.unimined.remapper.access

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.commonskt.reader.StringCharReader
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.remapper
import xyz.wagyourtail.unimined.mapping.formats.at.ATReader
import xyz.wagyourtail.unimined.mapping.formats.at.ATFormat
import xyz.wagyourtail.unimined.mapping.formats.at.LegacyATReader
import xyz.wagyourtail.unimined.mapping.formats.at.LegacyATWriter
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
            formats, spec.paths, spec.legacyAt
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
        explicitPaths: Set<String>,
        legacyAt: Boolean
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
                            try {
                                if (entry.name.endsWith(".mod.json")) {
                                    remapModJson(bytes, tree, sourceNs, targetNs)
                                } else when (kind(entry.name, bytes, explicit, formats)) {
                                    Kind.AW -> remapAw(bytes, tree, targetNs)
                                    Kind.AT -> remapAt(bytes, tree, sourceNs, targetNs, legacyAt)
                                    Kind.CT -> remapCt(bytes, tree, targetNs)
                                    null -> bytes
                                }
                            } catch (e: Exception) {
                                throw GradleException(
                                    "remap-access failed on entry ${entry.name}: ${e.message}", e)
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
        targetNs: Namespace,
        legacy: Boolean
    ): ByteArray {
        val out = StringBuilder()
        val useLegacy = legacy ||
            ATFormat.detect(String(bytes, Charsets.UTF_8)) == ATFormat.Kind.LEGACY
        val remapped = ATWriter.remapMappings(
            if (useLegacy)
                LegacyATReader.readData(
                    StringCharReader(bytes.toString(Charsets.UTF_8)), leinient = true)
            else
                ATReader.readData(
                    StringCharReader(bytes.toString(Charsets.UTF_8)), leinient = true),
            tree, sourceNs, targetNs
        )
        if (useLegacy) LegacyATWriter.writeData(remapped) { out.append(it) }
        else ATWriter.writeData(remapped) { out.append(it) }
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

    /**
     * Rebase `custom.*injected_interfaces` target keys inside `*.mod.json`
     * documents (fabric/quirk mod metadata) from [sourceNs] to
     * [targetNs]. The values are mod-owned interface names — never
     * remapped. Returns the input untouched when the document declares
     * no injections or the remap is identity, so non-fabric jars and
     * identity pipelines pass through byte-identical.
     */
    private fun remapModJson(
        bytes: ByteArray,
        tree: MappingTree,
        sourceNs: Namespace,
        targetNs: Namespace
    ): ByteArray {
        if (sourceNs == targetNs) return bytes
        val root = runCatching {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        }.getOrNull() ?: return bytes
        val custom = root["custom"] as? JsonObject ?: return bytes
        val injections = custom.filterKeys { it.endsWith("injected_interfaces") }
            .filterValues { it is JsonObject }
        if (injections.isEmpty()) return bytes
        val src = tree.namespaces.firstOrNull { it.name == sourceNs.name }
            ?: throw GradleException(
                "remap-access: source namespace '${sourceNs.name}' is not in the mapping tree"
            )
        val dst = tree.namespaces.firstOrNull { it.name == targetNs.name }
            ?: throw GradleException(
                "remap-access: target namespace '${targetNs.name}' is not in the mapping tree"
            )
        val remapper = tree.remapper(src, dst)
        val newCustom = LinkedHashMap<String, JsonElement>()
        for ((key, value) in custom) {
            val injected = injections[key] as? JsonObject
            newCustom[key] = if (injected == null) {
                value
            } else {
                JsonObject(
                    injected.mapKeys { (target, _) ->
                        remapper.mapClass(InternalName.unchecked(target)).value
                    }
                )
            }
        }
        val newRoot = LinkedHashMap(root)
        newRoot["custom"] = JsonObject(newCustom)
        return JsonObject(newRoot).toString().toByteArray()
    }
}
