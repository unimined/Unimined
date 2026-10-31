package xyz.wagyourtail.unimined.transform.forge.internal

import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.element.mapping.ClassMapping
import xyz.wagyourtail.unimined.mapping.element.mapping.ParameterMapping
import xyz.wagyourtail.unimined.transform.step.TransformStep
import xyz.wagyourtail.unimined.transform.step.TransformStepContext
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream

/**
 * `forge:coremodRemap` — rewrite srg-era names inside coremod text
 * resources (`.js`) from the pipeline's source namespace to its
 * target namespace.
 *
 * Why this exists: FML's JS coremod `ASMAPI.mapType`/`mapClass`
 * resolve through `MCPNamingService`, which only reads
 * `methods.csv`/`fields.csv` — it has no class-name channel (in
 * classic MCP-land srg class names equal mcp class names, so none
 * was needed). With dev namespaces that rename classes (yarn etc.)
 * class lookups have no data, and coremods embedding literal names
 * break regardless of `ASMAPI` usage. Rewriting the resource text
 * at build time covers both cases; dynamically constructed names
 * (`"func_" + id`) can't be caught — documented limitation.
 *
 * Requires `ctx.stepContext.mappings` (populated when the pipeline
 * includes a mapping-consuming step); without it the step passes
 * the jar through unchanged.
 */
object CoremodRemapTransformStep : TransformStep, Serializable {

    private const val serialVersionUID = 1L

    /** srg/searge member shapes — `func_1234_a`, `field_145_y`,
     *  `p_i654_1_`, plus short mcp-era `m_`/`f_`/`c_` forms. */
    private val MEMBER_RE = Regex(
        "\\b(?:func|field|m|f|c)_\\d+_[a-zA-Z]?\\b|\\bp_i\\d+_\\d+_\\b"
    )

    /** internal class paths — `a/b/C` (also matches inside `L..;`
     *  descriptors, which is what we want). */
    private val CLASS_PATH_RE = Regex(
        "\\b[a-zA-Z_$][\\w$]*(?:/[a-zA-Z_$][\\w$]*)+\\b"
    )

    /** dotted class names — lowercase package segments then a
     *  capitalized class segment: `a.b.C`, `a.b.C.D`. */
    private val CLASS_DOTTED_RE = Regex(
        "\\b(?:[a-z][\\w$]*\\.)+[A-Z][\\w$]*(?:\\.[\\w$]+)*\\b"
    )

    /**
     * Rewrite [text], replacing member tokens via [memberMap] then
     * class tokens (internal-path and dotted forms) via [classMap].
     * Whole-token anchored — substrings inside longer identifiers
     * are never touched.
     */
    internal fun rewriteCoremodText(
        text: String,
        classMap: Map<String, String>,
        memberMap: Map<String, String>
    ): String {
        var out = MEMBER_RE.replace(text) {
            memberMap[it.value] ?: it.value
        }
        out = CLASS_PATH_RE.replace(out) { m ->
            // `Lnet/x/Y` tokens carry the descriptor L prefix — try
            // the stripped form so L...; descriptors rewrite too
            classMap[m.value] ?: m.value.removePrefix("L").let {
                classMap[it]?.let { d -> "L$d" }
            } ?: m.value
        }
        return CLASS_DOTTED_RE.replace(out) {
            classMap[it.value] ?: it.value
        }
    }

    /**
     * Build `srg-ish -> target` maps from [tree]: class entries in
     * both internal and dotted forms, and name-only member entries
     * (srg member names are globally unique, so owner-less matching
     * is safe — the same convention coremod remappers use).
     */
    internal fun buildNameMaps(
        tree: MappingTree,
        src: String,
        dst: String
    ): Pair<Map<String, String>, Map<String, String>> {
        val srcNs = Namespace(src)
        val dstNs = Namespace(dst)
        val classMap = mutableMapOf<String, String>()
        val memberMap = mutableMapOf<String, String>()
        for (cls in tree.classes) {
            putClass(cls, srcNs, dstNs, classMap)
            for (m in cls.methods) {
                putMember(m.names, srcNs, dstNs, memberMap)
                for (e in m.contents) if (e is ParameterMapping) {
                    putMember(e.names, srcNs, dstNs, memberMap)
                }
            }
            for (f in cls.fields) {
                putMember(f.names, srcNs, dstNs, memberMap)
            }
        }
        return classMap to memberMap
    }

    private fun putClass(
        cls: ClassMapping, src: Namespace, dst: Namespace,
        out: MutableMap<String, String>
    ) {
        val s = cls.names[src]?.value ?: return
        val d = cls.names[dst]?.value ?: return
        if (s == d) return
        out[s] = d
        out[s.replace('/', '.')] = d.replace('/', '.')
    }

    private fun putMember(
        names: Map<Namespace, *>, src: Namespace, dst: Namespace,
        out: MutableMap<String, String>
    ) {
        val s = names[src]?.toString() ?: return
        val d = names[dst]?.toString() ?: return
        if (s != d) out[s] = d
    }

    private fun isCoremodResource(name: String): Boolean =
        name.endsWith(".js")

    override fun transform(input: Path, ctx: TransformStepContext): Path {
        val spec = ctx.stepContext.spec
        if (spec.sourceNamespace == spec.targetNamespace) return input
        ZipFile(input.toFile()).use { zip ->
            if (zip.entries().asSequence().none { isCoremodResource(it.name) })
                return input
        }
        val mappings = ctx.stepContext.mappings ?: return input
        val tree = mappings.view(MappingTree::class.java) ?: return input
        val (classMap, memberMap) = buildNameMaps(
            tree, spec.sourceNamespace, spec.targetNamespace
        )
        val out = ctx.workDir.resolve("coremodRemap.jar")
        Files.createDirectories(ctx.workDir)
        ZipFile(input.toFile()).use { zip ->
            ZipOutputStream(out.outputStream()).use { zos ->
                for (entry in zip.entries()) {
                    zos.putNextEntry(
                        java.util.zip.ZipEntry(entry.name).apply {
                            time = entry.time
                        }
                    )
                    if (!entry.isDirectory) {
                        val bytes = zip.getInputStream(entry).readBytes()
                        val data = if (isCoremodResource(entry.name)) {
                            rewriteCoremodText(
                                String(bytes), classMap, memberMap
                            ).toByteArray()
                        } else bytes
                        zos.write(data)
                    }
                    zos.closeEntry()
                }
            }
        }
        return out
    }

}
