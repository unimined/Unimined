package xyz.wagyourtail.unimined.mapping.processors

import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingPostProcessor
import xyz.wagyourtail.unimined.api.util.uniminedLogger

/**
 * Builtin [MappingPostProcessor] validating contributed
 * [MappingData.properties] — the "mappings-version compatibility
 * checks" builtin: sources carry stable version metadata (channel,
 * declared version, Minecraft version) and this stage turns a wrong
 * combination into a diagnosable configuration error.
 *
 * The ServiceLoader default runs with no expectations and only checks
 * the data is well-formed when properties are present. Environments register
 * configured instances —
 * `postProcessors.register(VersionCheckPostProcessor(mapOf("mcVersion" to mcVer)))`
 * — to assert the resolved mappings target what they expect.
 *
 * @param expected property keys and the values they must hold in the
 *  contributed metadata; an empty map checks nothing
 * @param strict when true (default) a mismatch fails resolution; when
 *  false it logs a warning and continues
 */
class VersionCheckPostProcessor(
    private val expected: Map<String, String> = emptyMap(),
    private val strict: Boolean = true
) : MappingPostProcessor {

    override val name = "version-check"

    override fun process(data: MappingData, ctx: MappingContext): MappingData {
        if (expected.isEmpty()) return data
        if (data.properties.isEmpty()) {
            report(
                ctx, "no mapping metadata was contributed for " +
                        "'${ctx.environmentId}'", detail = null
            )
            return data
        }
        val mismatches = expected.mapNotNull { (key, want) ->
            val got = data.properties[key]
            if (got == want) null else "$key: expected '$want', got '${got ?: "<absent>"}'"
        }
        if (mismatches.isNotEmpty()) {
            report(
                ctx,
                "mapping metadata for '${ctx.environmentId}' failed " +
                        "${mismatches.size} expected-value check(s)",
                mismatches.joinToString("; ")
            )
        }
        return data
    }

    private fun report(ctx: MappingContext, message: String, detail: String?) {
        val text = if (detail == null) message else "$message — $detail"
        if (strict) throw GradleException("[Unimined/Mappings] $text")
        uniminedLogger(ctx.environmentId, ctx.logger).subLogger("mappings").warning(text)
    }

}
