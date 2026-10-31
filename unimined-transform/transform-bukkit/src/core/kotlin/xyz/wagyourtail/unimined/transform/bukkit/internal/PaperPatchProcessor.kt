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

    private val patchPattern: Pattern =
        Pattern.compile("META-INF/.*/server-.*\\.patch")

    class Spec(val patchBytes: ByteArray) :
        JarProcessor.JarProcessorSpec, Serializable

    override fun buildSpec(context: JarProcessor.SpecContext): Spec {
        val jar = distJar()
        ZipFile(jar.toFile()).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull {
                it.name == "paperMC.patch" || patchPattern.matcher(it.name).matches()
            } ?: error(
                "paper dist jar $jar carries no server patch " +
                        "(expected paperMC.patch or META-INF/*/server-*.patch)"
            )
            return Spec(zip.getInputStream(entry).readBytes())
        }
    }

    override fun serializeSpec(spec: JarProcessor.JarProcessorSpec) =
        java.util.Base64.getEncoder().encodeToString((spec as Spec).patchBytes)

    override fun deserializeSpec(payload: String) =
        Spec(java.util.Base64.getDecoder().decode(payload))

    override fun processJar(
        jar: Path,
        spec: JarProcessor.JarProcessorSpec,
        context: JarProcessor.ProcessorContext
    ) {
        val patch = (spec as Spec).patchBytes
        val tmp = Files.createTempFile("unimined-paper-patch", ".jar")
        try {
            tmp.outputStream().use { out ->
                jar.inputStream().use { input ->
                    Patch.patch(input.readBytes(), patch, out)
                }
            }
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

}
