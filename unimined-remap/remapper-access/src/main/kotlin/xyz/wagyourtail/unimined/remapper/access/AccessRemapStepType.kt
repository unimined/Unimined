package xyz.wagyourtail.unimined.remapper.access

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.mapping.namedNamespaces
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
        val targetNs = Namespace(c.spec.targetNamespace)
        // loader-facing header label: fabric-family runtimes require the
        // canonical namespace names — resolve the spec's label map with
        // the same prefix-match the alias path uses for gen columns.
        // `devNamespaceLabel` defers the dev-ns lookup to now: the tree
        // is resolved here anyway, so wiring never parses mappings at
        // configuration time (mirrors devNamespace()'s heuristic)
        val labels = spec.devNamespaceLabel?.let { label ->
            val present = tree.namespaces.mapTo(HashSet()) { it.name }
            val devNs = c.mappings?.namedNamespaces()?.firstOrNull { it in present }
                ?: tree.namespaces.lastOrNull()?.name
            if (devNs != null) spec.namespaceLabels + (devNs to label)
            else spec.namespaceLabels
        } ?: spec.namespaceLabels
        val targetLabel = labels.entries
            .firstOrNull { (k, _) -> targetNs.name == k || targetNs.name.startsWith(k) }
            ?.value
        rewriteJar(
            input, output, tree,
            Namespace(c.spec.sourceNamespace), targetNs,
            formats, spec.paths, spec.legacyAt, spec.namespaceAliases,
            targetLabel
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
        legacyAt: Boolean,
        namespaceAliases: Map<String, String>,
        targetLabel: String? = null
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
                                    Kind.AW -> relabelHeader(
                                        remapAw(
                                            normalizeNs(bytes, tree, namespaceAliases, sourceNs),
                                            tree, targetNs
                                        ), targetNs, targetLabel
                                    )
                                    Kind.AT -> remapAt(bytes, tree, sourceNs, targetNs, legacyAt)
                                    Kind.CT -> relabelHeader(
                                        remapCt(
                                            normalizeNs(bytes, tree, namespaceAliases, sourceNs),
                                            tree, targetNs
                                        ), targetNs, targetLabel
                                    )
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


    /**
     * Rewrite the tab-separated namespace column of an AW/CT header when
     * it isn't a tree namespace — `namespaceAliases` maps the declared
     * name to the environment's prod channel (`intermediary` →
     * `calamus`); a column whose name merely *starts with* the alias
     * target also matches, covering gen-suffixed prod namespaces like
     * `calamusGen2`.
     */
    private fun normalizeNs(
        bytes: ByteArray, tree: MappingTree, aliases: Map<String, String>,
        fallback: Namespace
    ): ByteArray {
        val text = bytes.toString(Charsets.UTF_8)
        val nl = text.indexOf('\n')
        val header = if (nl >= 0) text.substring(0, nl) else text
        val cols = header.trim().split('\t')
        if (cols.size < 3) return bytes
        val declared = cols.last().trim()
        if (tree.namespaces.any { it.name == declared }) return bytes
        // embedded files are written in the jar's own (prod) namespace —
        // when the declared name isn't a tree column, aliases then the
        // pipeline's source namespace are the right resolution order.
        val target = aliases[declared]?.let { alias ->
            tree.namespaces.firstOrNull { it.name == alias || it.name.startsWith(alias) }?.name
        } ?: fallback.takeIf { f -> tree.namespaces.any { it.name == f.name } }?.name
          ?: return bytes
        val rest = if (nl >= 0) text.substring(nl) else ""
        return (cols.dropLast(1).joinToString("\t") + "\t" + target + rest)
            .toByteArray(Charsets.UTF_8)
    }

    /**
     * Rewrite the *output* header's declared namespace to the
     * loader-facing [label] — entries stay in [targetNs] names
     * (the loader applies rules by name against runtime classes; the
     * header column is only validated against the runtime namespace).
     * No-op when [label] is null or already equals the target name.
     */
    private fun relabelHeader(
        bytes: ByteArray, targetNs: Namespace, label: String?
    ): ByteArray {
        if (label == null || label == targetNs.name) return bytes
        val text = bytes.toString(Charsets.UTF_8)
        val nl = text.indexOf('\n')
        val header = if (nl >= 0) text.substring(0, nl) else text
        val cols = header.trim().split('\t')
        if (cols.size < 3) return bytes
        val rest = if (nl >= 0) text.substring(nl) else ""
        return (cols.dropLast(1).joinToString("\t") + "\t" + label + rest)
            .toByteArray(Charsets.UTF_8)
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
