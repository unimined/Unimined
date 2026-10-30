package xyz.wagyourtail.unimined.transform.forge.processor

import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevExtract
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessor.*
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

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

        // rewrite the jar: existing entries + injected (injected wins)
        val tmp = jar.resolveSibling("${jar.fileName}.tmp")
        JarOutputStream(tmp.toFile().outputStream()).use { out ->
            JarFile(jar.toFile()).use { input ->
                input.entries().asSequence().forEach { entry ->
                    // injected entries replace same-named originals
                    if (entry.name !in entries) {
                        out.putNextEntry(JarEntry(entry.name))
                        input.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
            for ((name, bytes) in entries) {
                context.log.subLogger("forge").info("injecting $name into minecraft jar")
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        tmp.toFile().renameTo(jar.toFile())
    }
}
