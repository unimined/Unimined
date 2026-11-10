package xyz.wagyourtail.unimined.jvmdg

import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import xyz.wagyourtail.jvmdg.ClassDowngrader
import xyz.wagyourtail.jvmdg.cli.Flags
import xyz.wagyourtail.jvmdg.compile.ApiShader
import xyz.wagyourtail.jvmdg.compile.ZipDowngrader
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import kotlin.reflect.KClass

/**
 * `downgrade` implementation backed by JvmDowngrader's
 * [ZipDowngrader]: rewrites every class in the artifact to the target
 * class-file version, backporting newer JDK API usage onto the
 * java-api stub jar.
 */
class JvmDowngradeStepType : RemapStepType<JvmDowngradeStepSpec> {

    override val name = "jvmdg"
    override val stepName = "downgrade"
    override val specType: KClass<JvmDowngradeStepSpec> = JvmDowngradeStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.JAR)

    /** jvmdg resolves supertypes/stubs against the run classpath. */
    override val needsClasspath = true

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: JvmDowngradeStepSpec, ctx: StepContext) = ArtifactStep { input, c ->
        val version = spec.classVersion.orNull ?: spec.target.orNull?.let {
            it.majorVersion.toInt() + 44
        } ?: throw GradleException(
            "downgrade: no target set — `downgrade { target.set(JavaVersion.VERSION_1_8) }` " +
                    "or `downgrade(JavaVersion.VERSION_1_8)` / `downgrade(52)`"
        )
        val workDir = Files.createTempDirectory(c.workDir, "jvmdg")
        val api = spec.api.files
        val flags = Flags().apply {
            classVersion = version
            // an empty api list leaves jvmdg auto-resolving the stub
            // jar over the network — the spec carries the resolved
            // file set so runs stay offline-reproducible
            if (api.isNotEmpty()) this.api = api.toList()
        }
        val downgraded = workDir.resolve("downgraded-${input.fileName}")
        ClassDowngrader.downgradeTo(flags).use { downgrader ->
            ZipDowngrader.downgradeZip(downgrader, input,
                c.classpath.map { it.toUri().toURL() }.toSet(), downgraded)
        }
        if (!spec.shade.getOrElse(true)) return@ArtifactStep downgraded
        // inline the used java-api stubs under the artifact's own
        // prefix so the produced jar is self-contained — jvmdg's own
        // `<basename>/` convention keeps shaded jars non-colliding
        val prefix = spec.shadePrefix.orNull
            ?: input.fileName.toString().removeSuffix(".jar")
                .replace(Regex("[.;\\[/]"), "-") + "/"
        val output = workDir.resolve(input.fileName.toString())
        ApiShader.shadeApis(flags, prefix, downgraded.toFile(),
            output.toFile(), api.map { withDowngradeTargetAttr(workDir, it) }.toSet())
        output
    }

    /**
     * `resolveDowngradedApi` re-runs `ZipDowngrader` over each api
     * jar unless its manifest carries `JvmDowngrader-Downgrade-Target`
     * — and that self-downgrade class-loads the newest stubs (`j25`
     * ships at v68), which fails on JVMs older than the newest stub.
     * The stubs are already era-compiled (higher-version ones land
     * under `META-INF/versions/` during shading), so stamp the
     * attribute and skip the load-failing pass.
     */
    private fun withDowngradeTargetAttr(workDir: java.nio.file.Path, apiJar: java.io.File): java.io.File {
        val patched = workDir.resolve("api-${apiJar.name}")
        java.util.jar.JarFile(apiJar).use { input ->
            java.util.jar.JarOutputStream(
                Files.newOutputStream(patched)).use { out ->
                input.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    out.putNextEntry(java.util.jar.JarEntry(entry.name))
                    if (entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) {
                        val mf = java.util.jar.Manifest(
                            input.getInputStream(entry)).apply {
                            mainAttributes.putValue(
                                "JvmDowngrader-Downgrade-Target", "45")
                        }
                        mf.write(out)
                    } else {
                        input.getInputStream(entry).copyTo(out)
                    }
                    out.closeEntry()
                }
            }
        }
        return patched.toFile()
    }

}
