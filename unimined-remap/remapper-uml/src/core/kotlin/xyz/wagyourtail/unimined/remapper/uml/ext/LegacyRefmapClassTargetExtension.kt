package xyz.wagyourtail.unimined.remapper.uml.ext

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.mapping.element.mapping.ClassMapping
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.remapper.ExtensionContext
import xyz.wagyourtail.unimined.mapping.remapper.asm.AsmExtension
import xyz.wagyourtail.unimined.mapping.remapper.ext.annotation.MemberRefParts

/**
 * Bridges legacy reobfuscated mixin target strings through shipped refmaps.
 *
 * Rift-era artifacts carry obfuscated bytecode and class literals, but keep
 * `@Mixin(targets = …)` strings in the compile namespace. Their refmap is
 * the bridge back to production/obfuscated names (`EnumConnectionState$1` →
 * `hx$1`); the ordinary source→target class remap cannot resolve the
 * compile-space string directly, so without this pass it survives verbatim
 * into the dev artifact and the runtime target class is absent.
 *
 * The rewrite is deliberately narrow and conservative: only `targets` on
 * `@Mixin`, only exact refmap keys for the input mixin class, and only when
 * the refmap value itself resolves as a class in this transform's source
 * namespace. Direct tree mappings still come from the main annotation
 * remapper; this extension fills the legacy hole rather than replacing it.
 */
class LegacyRefmapClassTargetExtension(
    private val ctx: ExtensionContext
) : AsmExtension {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    /** input mixin internal name → exact annotation string → refmap value */
    private val refmapEntries = mutableMapOf<String, MutableMap<String, String>>()

    override fun analyzeResource(path: String, bytes: ByteArray) {
        if (!path.endsWith("refmap.json") && !path.endsWith("-refmap.json")) return
        val root = runCatching {
            json.parseToJsonElement(bytes.decodeToString()).jsonObject
        }.getOrNull() ?: return
        val mappings = root["mappings"] as? JsonObject ?: return

        fun ingest(table: JsonObject, overwrite: Boolean) {
            for ((mixin, entries) in table) {
                val clsEntries = entries as? JsonObject ?: continue
                val dst = refmapEntries.getOrPut(mixin) { mutableMapOf() }
                for ((key, value) in clsEntries) {
                    val primitive = value as? JsonPrimitive ?: continue
                    if (!primitive.isString) continue
                    if (overwrite) dst[key] = primitive.content
                    else dst.putIfAbsent(key, primitive.content)
                }
            }
        }

        // Top-level `mappings` is the AP's default production table (often
        // notch), not necessarily this transform's source namespace. Hybrid
        // artifacts such as LiteLoader carry the actual compile→source bridge
        // under `data.<sourceNs>` (`data.searge`); those rows must win or a
        // production value like `D` is mistaken for a source-space member.
        ingest(mappings, overwrite = false)
        ((root["data"] as? JsonObject)?.get(ctx.fns.name) as? JsonObject)
            ?.let { ingest(it, overwrite = true) }
    }

    override fun postApply(input: ClassNode, output: ClassNode, cls: ClassMapping?) {
        val entries = refmapEntries[input.name] ?: return
        for (ann in output.visibleAnnotations.orEmpty() + output.invisibleAnnotations.orEmpty()) {
            rewriteAnnotation(ann, entries)
        }
        for (method in output.methods) {
            for (ann in method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty()) {
                rewriteAnnotation(ann, entries)
            }
        }
        for (field in output.fields) {
            for (ann in field.visibleAnnotations.orEmpty() + field.invisibleAnnotations.orEmpty()) {
                rewriteAnnotation(ann, entries)
            }
        }
    }

    /** Rewrite exact shipped-refmap strings recursively. Injection-point
     *  annotations are nested inside `@Inject`, so member selectors are not
     *  limited to the top-level `@Mixin` node. */
    private fun rewriteAnnotation(ann: AnnotationNode, entries: Map<String, String>) {
        val values = ann.values ?: return
        var i = 0
        while (i + 1 < values.size) {
            val key = values[i]
            val v = values[i + 1]
            values[i + 1] = when {
                key == "targets" && ann.desc == MIXIN_DESC -> rewriteValue(v, entries) {
                    remapTarget(it, entries)
                }
                else -> rewriteValue(v, entries) { remapMember(it, entries) }
            }
            i += 2
        }
    }

    private fun rewriteValue(value: Any?, entries: Map<String, String>, remap: (String) -> String): Any? = when (value) {
        is String -> remap(value)
        // nested annotations carry the injection-point selectors — rift's
        // hooks shape is `@Redirect(at = @At(target = …))`; dropping the
        // refmap entries here leaves every nested member selector unbridged
        is AnnotationNode -> value.also { rewriteAnnotation(it, entries) }
        is List<*> -> value.map {
            when (it) {
                is String -> remap(it)
                is AnnotationNode -> it.also { nested -> rewriteAnnotation(nested, entries) }
                else -> it
            }
        }
        else -> value
    }

    /** Map a compile-space member selector through its exact refmap value.
     *  The value is eligible only when it is a real member in this transform's
     *  source namespace: production refmap rows can share a class name with
     *  the source row while their single-letter/searge member belongs to a
     *  different namespace. Resolving those rows would overwrite an already
     *  correct dev-space selector with a foreign member (LiteLoader's
     *  `updateTimeLightAndEntities` → `D` failure). Resolved refs render in
     *  canonical owner-qualified form so obfuscated targets cannot over-match
     *  a bare single-letter selector. */
    private fun remapMember(value: String, entries: Map<String, String>): String {
        val mappedSource = entries[value] ?: return value
        val match = MEMBER_REF.matchEntire(mappedSource.trim()) ?: return value
        val owner = match.groupValues[1]
        val name = match.groupValues[2]
        val desc = match.groupValues[3]
        if (ctx.tree.getClass(ctx.fns, InternalName.unchecked(owner)) == null) return value
        val isMethod = desc.startsWith("(")
        val isField = !isMethod
        val memberDesc = if (isMethod) desc else desc.removePrefix(":")
        if (ctx.memberCandidateCount(owner, name, isField) == 0) return value
        if (ctx.resolveMemberDesc(owner, name, isField) != ctx.mapDesc(memberDesc, isField)) {
            return value
        }
        val parsed = MemberRefParts.parse(mappedSource, isField) ?: return value
        val resolved = parsed.resolve(ctx, emptyList(), isField) ?: return value
        return parsed.render(resolved)
    }

    private fun remapTarget(value: String, entries: Map<String, String>): String {
        val bridgedSource = entries[value]
            ?.trim()
            ?.removePrefix("L")
            ?.removeSuffix(";")
            ?.takeIf { it.isNotEmpty() && '(' !in it && ':' !in it && ';' !in it }
            ?: return value
        // Refmap values only bridge this transform when they name a row in
        // its source namespace. Alternate-environment rows (for example
        // production-obfuscation tables) must not rewrite dev annotations.
        if (ctx.tree.getClass(ctx.fns, InternalName.unchecked(bridgedSource)) == null) {
            return value
        }
        val mapped = ctx.mapClass(bridgedSource)
        if (mapped == bridgedSource) return value
        return if ('/' in value) mapped else mapped.replace('/', '.')
    }

    private companion object {
        const val MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;"
        val MEMBER_REF = Regex("^L([^;]+);([^;(:]+)((?:\\([^)]*\\).*)|(?::.+))$")
    }
}
