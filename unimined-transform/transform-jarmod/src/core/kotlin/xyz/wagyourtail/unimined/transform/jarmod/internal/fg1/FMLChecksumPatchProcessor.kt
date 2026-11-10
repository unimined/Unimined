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

    // empty artifacts → no spec: envs where the era wiring scanned no
    // dyn-libs (jarmod substrate on forge-family envs) must not enter
    // `entries` at all — its no-op processJar would still stamp the
    // run-once marker and `isApplied`-skip the real sums-carrying
    // instance later in the flattened list
    override fun buildSpec(context: JarProcessor.SpecContext): Spec? =
        artifacts().takeIf { it.isNotEmpty() }
            ?.let { Spec(it.mapValues { e -> sha1(e.value) }) }

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
            // the forge zip may already carry the tx splice (the
            // classpath patch path runs on the zip itself) — re-fixing
            // would collide on the injected `unimined$` method name
            val target = fs.getPath("cpw/mods/fml/relauncher/CoreFMLLibraries.class")
            val alreadySpliced = java.nio.file.Files.exists(target) && run {
                val cn = org.objectweb.asm.tree.ClassNode()
                org.objectweb.asm.ClassReader(
                    java.nio.file.Files.readAllBytes(target)).accept(cn, 0)
                cn.methods.any { it.name.startsWith("unimined$") }
            }
            if (!alreadySpliced) {
                ClassTransformJarFix.fix(fs, mapOf(
                "xyz.wagyourtail.unimined.transform.jarmod.internal.fg1.CoreFMLLibrariesChecksumTx" to
                    listOf("cpw/mods/fml/relauncher/CoreFMLLibraries.class")
            ), helpers = listOf(
                "xyz/wagyourtail/unimined/transform/jarmod/internal/fg1/rt/UniminedFg1ChecksumFix.class"
            ))
            }
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
