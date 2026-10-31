package xyz.wagyourtail.unimined.transform.bukkit.internal

import io.sigpipe.jbsdiff.Patch
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
    private val distJar: () -> Path
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
        val originalHash: ByteArray? = null,
        val outputHash: ByteArray? = null
    ) : JarProcessor.JarProcessorSpec, Serializable

    override fun buildSpec(context: JarProcessor.SpecContext): Spec {
        val jar = distJar()
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
            return Spec(patchBytes,
                hashes?.first, hashes?.second)
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

    override fun serializeSpec(spec: JarProcessor.JarProcessorSpec) =
        java.util.Base64.getEncoder().encodeToString((spec as Spec).patchBytes)

    override fun deserializeSpec(payload: String) =
        Spec(java.util.Base64.getDecoder().decode(payload))

    override fun processJar(
        jar: Path,
        spec: JarProcessor.JarProcessorSpec,
        context: JarProcessor.ProcessorContext
    ) {

        val tmp = Files.createTempFile("unimined-paper-patch", ".jar")
        try {
            // paperclip semantics: the patch input is the nested
            // META-INF/versions/<ver>/server-<ver>.jar INSIDE the
            // mojang jar (mojang ships a bundler too) — patching the
            // outer jar yields corrupt output. Fall back to the
            // whole jar when no nested entry exists (legacy layouts)
            val spec0 = spec as Spec
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
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
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
