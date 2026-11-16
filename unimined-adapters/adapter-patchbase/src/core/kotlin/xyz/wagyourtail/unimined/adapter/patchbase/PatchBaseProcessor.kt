package xyz.wagyourtail.unimined.adapter.patchbase

import xyz.wagyourtail.unimined.api.util.writeAtomically
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Applies PatchBase patch jars (`patchBase<env>` configuration) to the
 * environment's MC jar — the 2.0 counterpart of 1.x's
 * `MinecraftConfig.patchBase`/`customPatcher`. Runs pre-remap: cdiff
 * patches target prod-namespace classes (same constraint as
 * `jarmod:merge`).
 *
 * The env wiring resolves patch jars via `envArtifacts("patchbase")`
 * at spec-build; the spec carries `path:sha` entries so the cache key
 * tracks content and `processJar` can reopen them.
 */
class PatchBaseProcessor : JarProcessor {

    override val name = "patchbase"

    override val preRemap = true

    private data class Spec(
        val files: List<Entry>
    ) : JarProcessor.JarProcessorSpec {
        data class Entry(val path: String, val sha: String) : java.io.Serializable {
            companion object { private const val serialVersionUID = 1L }
        }
        companion object { private const val serialVersionUID = 1L }
    }

    override fun buildSpec(context: JarProcessor.SpecContext)
            : JarProcessor.JarProcessorSpec? {
        val jars = context.envArtifacts("patchbase")
        if (jars.isEmpty()) return null
        return Spec(jars.map { p ->
            Spec.Entry(p.toAbsolutePath().toString(),
                MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(p))
                    .joinToString("") { "%02x".format(it) })
        })
    }

    override fun serializeSpec(spec: JarProcessor.JarProcessorSpec): String =
        (spec as Spec).files.joinToString(";") { "${it.path}:${it.sha}" }

    override fun deserializeSpec(payload: String)
            : JarProcessor.JarProcessorSpec = Spec(
        payload.split(";").filter { it.isNotBlank() }.map {
            val i = it.lastIndexOf(':')
            Spec.Entry(it.substring(0, i), it.substring(i + 1))
        }
    )

    override fun processJar(
        jar: Path, spec: JarProcessor.JarProcessorSpec,
        context: JarProcessor.ProcessorContext
    ) {
        for (entry in (spec as Spec).files) {
            val patchJar = Path.of(entry.path)
            jar.writeAtomically { tmp ->
                xyz.wagyourtail.patchbase.installer.PatchbaseInstaller()
                    .patch(patchJar, jar, tmp)
            }
            context.log.lifecycle(
                "[Unimined/patchbase] applied ${patchJar.fileName}")
        }
    }

}
