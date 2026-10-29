package xyz.wagyourtail.unimined.transform

import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessorRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.reflect.KClass

/**
 * `jar-processors` implementation — copies the artifact into the work
 * dir, then invokes each declared processor's
 * [JarProcessor.processJar] in spec order (Loom's
 * `MinecraftJarProcessorManager` jar stage).
 *
 * Processors resolve by name through [JarProcessorRegistry] builtins,
 * so impls must be ServiceLoader'd on the plugin classpath.
 */
class JarProcessorsStepType : RemapStepType<JarProcessorsStepSpec> {

    override val name = "unimined"
    override val stepName = "jar-processors"
    override val specType: KClass<JarProcessorsStepSpec> = JarProcessorsStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.JAR)

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: JarProcessorsStepSpec, ctx: StepContext) = ArtifactStep { input, c ->
        val entries = spec.processorSpecs.getOrElse(emptyList())
        if (entries.isEmpty()) return@ArtifactStep input

        val output = Files.createTempDirectory(c.workDir, "jarproc")
            .resolve(input.fileName.toString())
        Files.copy(input, output)

        val mods = spec.modJars.files.map { BasicModJarInfo(it.toPath()) }
        val context = object : JarProcessor.ProcessorContext {
            override val logger = Logging.getLogger(JarProcessorsStepType::class.java)
            override val log = c.log.subLogger("jar-processors")
            override val mappings: MappingData? = c.mappings
            override fun mods() = mods
        }

        for (entry in entries) {
            val name = entry.substringBefore(' ')
            val payload = entry.substringAfter(' ')
            val processor = JarProcessorRegistry.resolveBuiltin(name)
                ?: throw GradleException(
                    "jar processor '$name' is not on the plugin classpath"
                )
            processor.processJar(output, processor.deserializeSpec(payload), context)
        }
        output
    }

    /**
     * Minimal execution-side [JarProcessor.ModJarInfo] — jar reads work,
     * loader metadata doesn't (spec payloads already carry everything
     * `buildSpec` extracted; `custom` returns `null`).
     */
    class BasicModJarInfo(override val jar: Path) : JarProcessor.ModJarInfo {

        override val id: String? get() = null

        override fun custom(key: String): String? = null

        override fun readEntry(path: String): ByteArray? = runCatching {
            JarFile(jar.toFile()).use { jarFile ->
                jarFile.getJarEntry(path)?.let { jarFile.getInputStream(it).readBytes() }
            }
        }.getOrNull()

    }

}
