package xyz.wagyourtail.unimined.remap.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import xyz.wagyourtail.unimined.api.mapping.namedNamespaces
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.api.util.FabricNamespaces
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.FusedJarEntry
import xyz.wagyourtail.unimined.remap.spi.FusibleJarStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import xyz.wagyourtail.unimined.remap.spi.StripNestedJarsStepSpec
import kotlin.reflect.KClass

/**
 * `strip-nested-jars` step — the [StripNestedJarsStepSpec] engine,
 * appended to dependency remapped views only. Rewrites `*.mod.json`
 * entries in place, dropping top-level `jars` and `quilt_loader.jars`
 * arrays, and rewrites the manifest's `Fabric-Mapping-Namespace`
 * attribute to the pipeline's target namespace — a remapped dep jar
 * bound to `named`/`mojmap` must not keep declaring `intermediary`
 * (fabric-loader reads it for its runtime-remap decision). Identity-
 * safe for jars without mod metadata, JiJ declarations, or the
 * attribute (byte-verbatim pass-through).
 */
class StripNestedJarsStepType : RemapStepType<StripNestedJarsStepSpec> {

    override val name = "core"
    override val stepName = "strip-nested-jars"
    override val specType: KClass<StripNestedJarsStepSpec> =
        StripNestedJarsStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.JAR)

    override fun supports(spec: RemapSpec) = true

    override fun create(
        spec: StripNestedJarsStepSpec,
        ctx: StepContext
    ): ArtifactStep = object : FusibleJarStep {
        override fun visitEntry(
            entry: FusedJarEntry,
            c: StepContext
        ): FusedJarEntry {
            if (entry.name == "META-INF/MANIFEST.MF") {
                val bytes = fixMappingNamespace(entry.bytes, c) ?: return entry
                return FusedJarEntry(entry.name, bytes)
            }
            if (!entry.name.endsWith(".mod.json")) return entry
            val bytes = stripJarsKeys(entry.bytes) ?: return entry
            return FusedJarEntry(entry.name, bytes)
        }
    }

    /** Returns rewritten bytes, or `null` when nothing needed
     *  stripping (unparseable, no `jars` keys). */
    private fun stripJarsKeys(bytes: ByteArray): ByteArray? {
        val root = runCatching {
            Json.parseToJsonElement(
                bytes.toString(Charsets.UTF_8)) as? JsonObject
        }.getOrNull() ?: return null

        var changed = false
        val newRoot = LinkedHashMap(root)
        if (newRoot.remove("jars") != null) changed = true
        // quilt nests it: {"quilt_loader": {"jars": [...]}}
        (newRoot["quilt_loader"] as? JsonObject)?.let { loader ->
            val newLoader = LinkedHashMap(loader)
            if (newLoader.remove("jars") != null) {
                newRoot["quilt_loader"] = JsonObject(newLoader)
                changed = true
            }
        }
        if (!changed) return null
        return JsonObject(newRoot).toString().toByteArray()
    }

    /** Rewrite `Fabric-Mapping-Namespace` to the pipeline target —
     *  canonicalized to fabric's `official`/`intermediary`/`named`
     *  vocabulary (internal names like `mojmap` must not leak into a
     *  fabric-ecosystem attribute). Only when the attribute exists
     *  and canonically disagrees (absent stays absent: fabric-loader's
     *  default assumption is the correct behavior for jars that never
     *  declared it). */
    private fun fixMappingNamespace(bytes: ByteArray, c: StepContext): ByteArray? {
        val mf = runCatching {
            java.util.jar.Manifest(bytes.inputStream())
        }.getOrNull() ?: return null
        val attrs = mf.mainAttributes
        val declared = attrs.getValue("Fabric-Mapping-Namespace") ?: return null
        val named = c.mappings?.namedNamespaces()?.toSet() ?: emptySet()
        val target = FabricNamespaces.canonical(c.spec.targetNamespace, named)
        if (FabricNamespaces.canonical(declared, named) == target) return null
        attrs.putValue("Fabric-Mapping-Namespace", target)
        return java.io.ByteArrayOutputStream()
            .also { mf.write(it) }.toByteArray()
    }

}
