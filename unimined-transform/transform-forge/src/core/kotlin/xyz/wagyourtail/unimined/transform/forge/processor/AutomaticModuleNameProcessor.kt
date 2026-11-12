package xyz.wagyourtail.unimined.transform.forge.processor

import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessor.*
import java.io.ByteArrayOutputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest

/**
 * `automatic-module-name` jar processor — writes an
 * `Automatic-Module-Name` manifest attribute into the dev jar
 * (merging into an existing manifest, creating a minimal one when
 * the jar has none). SecureJarHandler-era launches resolve the
 * game jar's module name from it — the forge dev jar is otherwise
 * named `forge-<v>-merged` and the `minecraft` module the bootstrap
 * requires never materializes.
 *
 * Spec payloads are contributed by the environment wiring, not
 * [buildSpec] — the module name is loader-configured, not
 * jar-discovered.
 */
class AutomaticModuleNameProcessor : JarProcessor {

    override val name = "automatic-module-name"

    override fun buildSpec(context: SpecContext): JarProcessorSpec? = null

    data class Spec(
        val moduleName: String,
        /** Optional jar whose manifest donates version-bearing
         * `Name:` sections (e.g. `net/minecraftforge/versions/forge/`
         * — FML's `JarVersionLookupHandler` resolves
         * `Package.getSpecificationVersion`/`getImplementationVersion`
         * from them, which `ForgeVersion.<clinit>` hard-requires). */
        val manifestDonor: String? = null,
    ) : JarProcessorSpec {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    override fun serializeSpec(spec: JarProcessorSpec): String =
        (spec as Spec).let { s ->
            s.manifestDonor?.let { "${s.moduleName} $it" } ?: s.moduleName
        }

    override fun deserializeSpec(payload: String): JarProcessorSpec =
        payload.trim().split(' ', limit = 2).let {
            Spec(it[0], it.getOrNull(1))
        }

    override fun processJar(jar: Path, spec: JarProcessorSpec, context: ProcessorContext) {
        val s = spec as Spec
        JarFile(jar.toFile()).use { jf ->
            val existing = jf.getEntry("META-INF/MANIFEST.MF")
                ?.let { jf.getInputStream(it).use { ins -> Manifest(ins) } }
                ?: Manifest()
            if (existing.mainAttributes.getValue("Automatic-Module-Name") == s.moduleName)
                return
            existing.mainAttributes.putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0")
            existing.mainAttributes.putValue("Automatic-Module-Name", s.moduleName)

            // donate version-bearing `Name:` sections from the
            // loader jar (universal) — `*-Digest` attributes are
            // dropped: they hash pre-remap entry bytes and would be
            // stale here
            s.manifestDonor?.let { donor ->
                JarFile(donor).use { j ->
                    val entry = j.getEntry("META-INF/MANIFEST.MF") ?: return@use
                    val donorMf = j.getInputStream(entry).use { Manifest(it) }
                    donorMf.entries.forEach { (name, attrs) ->
                        val wants = attrs.keys.any {
                            it.toString() == "Specification-Version" ||
                                    it.toString() == "Implementation-Version"
                        }
                        if (wants) {
                            val out = Attributes()
                            attrs.forEach { (k, v) ->
                                if (!k.toString().contains("Digest")) out[k] = v
                            }
                            existing.entries[name] = out
                        }
                    }
                }
            }

            // zipfs in-place manifest swap — rewriting all ~2000
            // entries to change one manifest pays a serial deflate
            // pass over the whole jar for a few hundred bytes of
            // work; delete+write of the single entry leaves every
            // other entry's compressed bytes untouched
            val mfBytes = ByteArrayOutputStream()
                .also { existing.write(it) }.toByteArray()
            FileSystems.newFileSystem(jar, emptyMap<String, Any>()).use { fs ->
                val p = fs.getPath("META-INF/MANIFEST.MF")
                Files.deleteIfExists(p)
                p.parent?.let { Files.createDirectories(it) }
                Files.write(p, mfBytes, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)
                Files.setLastModifiedTime(p, FileTime.fromMillis(0))
            }
            context.log.subLogger("forge").info(
                "set Automatic-Module-Name: ${s.moduleName} on minecraft jar"
            )
        }
    }
}
