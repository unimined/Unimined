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
 * `JarModMinecraftTransformer.transform` shade). Required on
 * launchwrapper-era (notchObf) userdev — FML classes must live in the
 * dev jar itself; modern userdev leaves the loader on the module
 * path and declares no shade requirement.
 *
 * `universalFilters` are include-regexes over entry names (FG's own
 * semantic — e.g. `^(?!binpatches\.pack\.lzma$).*$`); an empty list
 * shades everything. `META-INF` (signature debris) and
 * `module-info.class` are always skipped.
 */
class UserdevUniversalShadeProcessor : JarProcessor {

    override val name = "userdev-universal-shade"

    override fun buildSpec(context: SpecContext): JarProcessorSpec? = null

    data class Spec(val jar: Path, val filters: List<String>) : JarProcessorSpec {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /** `filter\n...` lines then `jarPath` — the universal jar is
     *  fingerprinted via the step's `modJars` inputs, so the payload
     *  only needs identity. */
    override fun serializeSpec(spec: JarProcessorSpec): String {
        val s = spec as Spec
        return (s.filters + s.jar.toAbsolutePath().toString()).joinToString("\n")
    }

    override fun deserializeSpec(payload: String): JarProcessorSpec {
        val lines = payload.split("\n")
        return Spec(Paths.get(lines.last()), lines.dropLast(1))
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
                    if (e.name == "META-INF/MANIFEST.MF" ||
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
