package xyz.wagyourtail.unimined.transform.bukkit.internal

import io.sigpipe.jbsdiff.Patch
import xyz.wagyourtail.unimined.api.util.writeAtomically
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import java.util.zip.ZipFile
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Applies paper's embedded binary patch to the vanilla server jar
 * (1.x `PaperMinecraftTransformer.transform` parity): paper dist
 * jars ship a jbsdiff `server-*.patch` under `META-INF/` (or the
 * top-level `paperMC.patch`); patching produces the spigot-
 * namespace paper server jar, which then rides the standard
 * raw→dev remap view.
 *
 * The patch bytes ship inside the serialized spec — they're
 * era-versioned data (~50–500KB), not configuration.
 */
internal class PaperPatchProcessor(
    /** Config-time dist jar supplier; null on the ServiceLoader instance
     *  (only `buildSpec` needs it — execution rides the serialized spec). */
    private val distJar: (() -> Path)? = null,
    /** Namespace the patched jar comes out in — `mojmap` on paper
     *  >=1.20.5 (mojang-mapped runtime), `spigotProd` before. Config-time
     *  only; the value travels inside the serialized spec. */
    private val outputNamespace: (() -> String)? = null
) : JarProcessor {

    override val name = "paper-patch"
    override val preRemap = true

    private val patchPatterns = listOf(
        Pattern.compile("META-INF/.*/server-.*\\.patch"),
        // paperclip bundler (paper >=1.20.4): META-INF/versions/<ver>/paper-<ver>.patch
        Pattern.compile("META-INF/versions/[^/]+/[^/]+\\.patch")
    )

    class Spec(
        val patchBytes: ByteArray,
        val outputNamespace: String,
        val originalHash: ByteArray? = null,
        val outputHash: ByteArray? = null
    ) : JarProcessor.JarProcessorSpec, Serializable

    override fun buildSpec(context: JarProcessor.SpecContext): Spec {
        val jar = requireNotNull(distJar) {
            "PaperPatchProcessor.buildSpec runs at config time — " +
                "the ServiceLoader instance is deserialize/process only"
        }.invoke()
        ZipFile(jar.toFile()).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull {
                it.name == "paperMC.patch" ||
                    patchPatterns.any { p -> p.matcher(it.name).matches() }
            } ?: error(
                "paper dist jar $jar carries no server patch " +
                        "(expected paperMC.patch, META-INF/*/server-*.patch, " +
                        "or META-INF/versions/*/*.patch)"
            )
            val patchBytes = zip.getInputStream(entry).readBytes()
            // bundler jars carry patches.list with per-patch
            // original/output sha256 — paperclip verifies both
            val hashes = zip.getEntry("META-INF/patches.list")
                ?.let { patchesList(zip, it) }
            return Spec(
                patchBytes,
                requireNotNull(outputNamespace) {
                    "PaperPatchProcessor.buildSpec runs at config time — " +
                        "the ServiceLoader instance is deserialize/process only"
                }.invoke(),
                hashes?.first, hashes?.second
            )
        }
    }

    private fun patchesList(zip: ZipFile, entry: java.util.zip.ZipEntry)
            : Pair<ByteArray, ByteArray>? =
        zip.getInputStream(entry).bufferedReader().useLines { lines ->
            lines.map { it.split('\t') }
                .firstOrNull { it.size >= 7 && it[0] == "versions" }
                ?.let {
                    it[1].toSha() to it[3].toSha()
                }
        }

    private fun String.toSha(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    override fun serializeSpec(spec: JarProcessor.JarProcessorSpec): String {
        spec as Spec
        // first line: output namespace + optional integrity hashes;
        // remainder: the patch payload
        return buildString {
            append(spec.outputNamespace)
            spec.originalHash?.let { append(' ').append(it.toHex()) }
            spec.outputHash?.let { append(' ').append(it.toHex()) }
            append('\n')
            append(java.util.Base64.getEncoder().encodeToString(spec.patchBytes))
        }
    }

    override fun deserializeSpec(payload: String): Spec {
        val head = payload.substringBefore('\n')
        val toks = head.split(' ')
        fun hex(i: Int) = toks.getOrNull(i)
            ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
        return Spec(
            java.util.Base64.getDecoder().decode(payload.substringAfter('\n')),
            toks.first(), hex(1), hex(2)
        )
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    override fun processJar(
        jar: Path,
        spec: JarProcessor.JarProcessorSpec,
        context: JarProcessor.ProcessorContext
    ) {

        val spec0 = spec as Spec
        jar.writeAtomically { tmp ->
            // paperclip semantics: the patch input is the nested
            // META-INF/versions/<ver>/server-<ver>.jar INSIDE the
            // mojang jar (mojang ships a bundler too) — patching the
            // outer jar yields corrupt output. Fall back to the
            // whole jar when no nested entry exists (legacy layouts)
            val base = nestedServerJar(jar) ?: jar.inputStream().use { it.readBytes() }
            if (spec0.originalHash != null) {
                check(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(base).contentEquals(spec0.originalHash)) {
                    "paper patch input hash mismatch — the jar is not " +
                        "the expected bundled vanilla form"
                }
            }
            tmp.outputStream().use { out ->
                Patch.patch(base, spec0.patchBytes, out)
            }
            if (spec0.outputHash != null) {
                check(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(tmp))
                    .contentEquals(spec0.outputHash)) {
                    "paper patch output hash mismatch — " +
                        "patched jar does not match patches.list"
                }
            }
        }
        // the patch changes the jar's namespace entirely — remap
        // must source from the patched namespace, not `official`
        context.state?.currentNamespace = spec0.outputNamespace
    }

    /** The mojang server jar is itself a bundler —
     * `META-INF/versions/<ver>/server-<ver>.jar` nested inside is
     * the real patch base (paperclip resolves
     * `META-INF/<location>/<originalPath>` against the input jar's
     * own filesystem). */
    private fun nestedServerJar(jar: Path): ByteArray? = try {
        ZipFile(jar.toFile()).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull {
                Pattern.compile("META-INF/versions/[^/]+/server-[^/]+\\.jar")
                    .matcher(it.name).matches()
            }
            entry?.let { zip.getInputStream(it).readBytes() }
        }
    } catch (e: java.util.zip.ZipException) {
        null
    }

}
