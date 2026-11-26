package xyz.wagyourtail.unimined.transform.forge.processor

import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessor.*
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.util.jar.JarFile

/**
 * `userdev-universal-shade` jar processor — overlays the userdev
 * `universal` artifact's entries into the patched Minecraft jar (1.x
 * `JarModMinecraftTransformer.transform` shade). Legacy and
 * FML-10/11-era userdev need loader classes inside the dev jar;
 * post-1.21.11 NeoForge does not use this processor at all because
 * the universal stays a separate classpath root.
 *
 * `universalFilters` are include-regexes over entry names (FG's own
 * semantic — e.g. `^(?!binpatches\.pack\.lzma$).*$`); an empty list
 * shades everything. Signature debris, patch packs, and
 * `module-info.class` are always skipped. The loader manifest is
 * skipped in the legacy shape but retained when [Spec.keepManifest]
 * is set for FML 10/11's `FML-System-Mods` scan; other META-INF
 * content (mods.toml, services, access transformers) survives.
 */
class UserdevUniversalShadeProcessor : JarProcessor {

    override val name = "userdev-universal-shade"

    override fun buildSpec(context: SpecContext): JarProcessorSpec? = null

    data class Spec(
        val jar: Path,
        val filters: List<String>,
        /** Keep the universal's `META-INF/MANIFEST.MF` — FML 10+
         *  discovers NeoForge by its `FML-System-Mods` attribute. */
        val keepManifest: Boolean = false
    ) : JarProcessorSpec {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /** `filter\n...` lines then `jarPath`, then the keep-manifest
     *  boolean — the universal jar is fingerprinted via the step's
     *  `modJars` inputs, so the payload only needs identity. The final
     *  boolean is optional when reading legacy payloads. */
    override fun serializeSpec(spec: JarProcessorSpec): String {
        val s = spec as Spec
        return (s.filters + s.jar.toAbsolutePath().toString() +
                s.keepManifest.toString()).joinToString("\n")
    }

    override fun deserializeSpec(payload: String): JarProcessorSpec {
        val lines = payload.split("\n")
        val keepManifest = lines.last().toBooleanStrictOrNull() == true
        val jarLine = if (lines.last().toBooleanStrictOrNull() != null)
            lines[lines.lastIndex - 1] else lines.last()
        val filterCount = lines.lastIndex - if (
            lines.last().toBooleanStrictOrNull() != null) 1 else 0
        return Spec(
            Paths.get(jarLine),
            lines.take(filterCount),
            keepManifest
        )
    }

    override fun processJar(jar: Path, spec: JarProcessorSpec, context: ProcessorContext) {
        val s = spec as Spec
        val patterns = s.filters.map { Regex(it) }
        val entries = LinkedHashMap<String, ByteArray>()
        JarFile(s.jar.toFile()).use { src ->
            for (e in src.entries()) {
                if (e.isDirectory) continue
                if (e.name == "module-info.class") continue
                // patch packs never reach runtime — the runtime
                // ClassPatchManager checksums the pre-patch jar and
                // fatals on an already-patched dev jar (1.x removed
                // `binpatches.pack.lzma` unconditionally, incl. when
                // `universalFilters` is empty)
                if (e.name == "binpatches.pack.lzma" ||
                    e.name == "devbinpatches.pack.lzma") continue
                if (e.name.startsWith("META-INF")) {
                    // drop the manifest + signature files only —
                    // META-INF/mods.toml, coremods.json, services/ and
                    // accesstransformer.cfg are runtime-load-bearing
                    // (forge mod discovery + FML service bindings)
                    if ((e.name == "META-INF/MANIFEST.MF" && !s.keepManifest) ||
                        e.name.endsWith(".SF") || e.name.endsWith(".RSA") ||
                        e.name.endsWith(".DSA") || e.name.endsWith(".EC")
                    ) continue
                }
                if (patterns.isNotEmpty() && patterns.none { it.matches(e.name) }) continue
                entries[e.name] = src.getInputStream(e).readBytes()
            }
        }
        if (entries.isEmpty()) return

        // in-place zipfs update — the loader's entries win
        // (1.x REPLACE_EXISTING) via delete+write, and untouched MC
        // entries keep their original compressed bytes instead of
        // paying a full-jar serial deflate. DOS-epoch stamps keep the
        // output byte-deterministic.
        FileSystems.newFileSystem(jar, emptyMap<String, Any>()).use { fs ->
            for ((name, bytes) in entries) {
                val p = fs.getPath(name)
                Files.deleteIfExists(p)
                p.parent?.let { Files.createDirectories(it) }
                Files.write(p, bytes, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)
                Files.setLastModifiedTime(p, FileTime.fromMillis(0))
            }
        }
        context.log.subLogger("forge").info(
            "shaded ${entries.size} universal entries into minecraft jar"
        )
    }
}
