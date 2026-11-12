package xyz.wagyourtail.unimined.transform.forge.processor

import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevExtract
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessor.*
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime

/**
 * `userdev-inject` jar processor — copies the userdev installer jar's
 * `inject`-prefix entries into the patched Minecraft jar (1.x
 * `FG3MinecraftTransformer` `inject` transform: prefix stripped,
 * `REPLACE_EXISTING`).
 *
 * Spec payloads are contributed by the environment wiring, not
 * [buildSpec] — the installer jar and prefix come from the probed
 * `Userdev` profile, not the mod batch.
 */
class UserdevInjectProcessor : JarProcessor {

    override val name = "userdev-inject"

    override fun buildSpec(context: SpecContext): JarProcessorSpec? = null

    data class Spec(val jar: Path, val prefix: String) : JarProcessorSpec {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /** `prefix\njarPath` — the installer jar is fingerprinted via the
     *  step's `modJars` inputs, so the payload only needs identity. */
    override fun serializeSpec(spec: JarProcessorSpec): String {
        val s = spec as Spec
        return "${s.prefix}\n${s.jar.toAbsolutePath()}"
    }

    override fun deserializeSpec(payload: String): JarProcessorSpec {
        val (prefix, jar) = payload.split("\n", limit = 2)
        return Spec(Paths.get(jar), prefix)
    }

    override fun processJar(jar: Path, spec: JarProcessorSpec, context: ProcessorContext) {
        val s = spec as Spec
        val entries = UserdevExtract.injectEntries(s.jar, s.prefix)
        if (entries.isEmpty()) return

        // in-place zipfs update — rewriting the whole jar to inject a
        // handful of entries pays a serial deflate pass over ~50 MB for
        // work that is O(injected): zipfs appends only the new entries
        // (delete+write = REPLACE_EXISTING) and leaves untouched entries'
        // compressed bytes alone. It also fixes a determinism bug the
        // rewrite carried — `JarEntry(name)` stamped wall-clock times —
        // by setting DOS-epoch on the written entries.
        FileSystems.newFileSystem(jar, emptyMap<String, Any>()).use { fs ->
            for ((name, bytes) in entries) {
                context.log.subLogger("forge").info("injecting $name into minecraft jar")
                val p = fs.getPath(name)
                Files.deleteIfExists(p)
                p.parent?.let { Files.createDirectories(it) }
                Files.write(p, bytes, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)
                Files.setLastModifiedTime(p, FileTime.fromMillis(0))
            }
        }
    }
}
