package xyz.wagyourtail.unimined.remap.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import xyz.wagyourtail.unimined.api.spec.RemapSpec
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
 * arrays. Identity-safe for jars without mod metadata or without JiJ
 * declarations (byte-verbatim pass-through).
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

}
