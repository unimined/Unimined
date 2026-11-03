package xyz.wagyourtail.unimined.transform.jarmod.internal.fg1

import xyz.wagyourtail.commonskt.utils.toHex
import xyz.wagyourtail.unimined.transform.internal.classtransform.ClassTransformJarFix
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.io.File
import java.io.Serializable
import java.nio.file.FileSystems
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Rewrites `CoreFMLLibraries.checksums` so FML's downloader accepts
 * the artifacts we actually ship in `runDir/lib`. The class holds
 * parallel `libraries[]`/`checksums[]` `String` arrays built in
 * `<clinit>`; for every shipped lib whose content differs from the
 * era's original (asm-5.2 substitution, maven-resolved fmllibs) the
 * matching `checksums` LDC is replaced with our artifact's SHA1 —
 * integrity checking stays on, real corruption still fails the hash.
 * Non-LDC slots (e.g. `FMLInjectionData.deobfuscationDataHash` on
 * 1.5.2) keep their expression — only index alignment matters.
 *
 * The class-content patch lives in [CoreFMLLibrariesChecksumTx] —
 * a `@CTransformer` applied build-time through [ClassTransformJarFix]
 * (same channel as the fg2 dev fixes). The resolved SHA1s ship inside
 * the jar as `unimined/fg1-lib-sums.txt`, read at runtime by the
 * spliced [CoreFMLLibrariesChecksumTx]'s helper.
 */
internal class FMLChecksumPatchProcessor(
    private val artifacts: () -> Map<String, File> = { emptyMap() }
) : JarProcessor {

    override val name = "fg1-checksum-patch"
    override val preRemap = true

    class Spec(val sums: Map<String, String>) : JarProcessor.JarProcessorSpec, Serializable

    override fun buildSpec(context: JarProcessor.SpecContext) =
        Spec(artifacts().mapValues { sha1(it.value) })

    override fun serializeSpec(spec: JarProcessor.JarProcessorSpec) =
        (spec as Spec).sums.entries.joinToString(";") { "${it.key}=${it.value}" }

    override fun deserializeSpec(payload: String) = Spec(
        if (payload.isEmpty()) emptyMap()
        else payload.split(";").associate { it.substringBefore('=') to it.substringAfter('=') }
    )

    override fun processJar(
        jar: Path,
        spec: JarProcessor.JarProcessorSpec,
        context: JarProcessor.ProcessorContext
    ) {
        val sums = (spec as Spec).sums
        if (sums.isEmpty()) return
        FileSystems.newFileSystem(jar).use { fs ->
            // the spliced helper reads this at clinit time
            val res = fs.getPath("unimined/fg1-lib-sums.txt")
            java.nio.file.Files.createDirectories(res.parent)
            java.nio.file.Files.writeString(res,
                sums.entries.joinToString("\n") { "${it.key}=${it.value}" })
            ClassTransformJarFix.fix(fs, mapOf(
                "xyz.wagyourtail.unimined.transform.jarmod.internal.fg1.CoreFMLLibrariesChecksumTx" to
                    listOf("cpw/mods/fml/relauncher/CoreFMLLibraries.class")
            ), helpers = listOf(
                "xyz/wagyourtail/unimined/transform/jarmod/internal/fg1/rt/UniminedFg1ChecksumFix.class"
            ))
        }
    }

    private fun sha1(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        f.inputStream().use { s ->
            val buf = ByteArray(8192)
            while (true) {
                val n = s.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }
}
