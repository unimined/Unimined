package xyz.wagyourtail.unimined.transform

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.minecraft.merge.BundledLibraryStrip
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.DevArtifactOnlyStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import kotlin.reflect.KClass

/**
 * Spec for the `strip-bundled-libraries` step: drops jar entries whose
 * paths exist inside a resolved library jar — the membership strip
 * [BundledLibraryStrip] shares with [xyz.wagyourtail.unimined.minecraft
 * .merge.JarMerger]'s server-side rule, as a pipeline step on the
 * environment's Minecraft artifact.
 *
 * Environment wiring contributes this when a product declares
 * replacement libraries for jars it shades (e.g. conversion dists
 * embedding slf4j/log4j) — the declared copies ride the classpath and
 * the bundled duplicates strip out. `META-INF/MANIFEST.MF` and
 * `META-INF/services/` entries never match (registrations aren't
 * library code — see [BundledLibraryStrip.entryNames]).
 *
 * [DevArtifactOnlyStepSpec]: the membership set is the environment's
 * declared Minecraft libraries — stripping a production jar or a mod
 * dependency against it would delete that jar's own classes.
 */
abstract class BundledLibraryStripStepSpec : DevArtifactOnlyStepSpec {
    @get:Input
    @get:Optional
    override val engine: String?
        get() = null

    /**
     * Resolved library jars whose entries strip out of the artifact —
     * the provider's *declared* library set (version-json deps plus
     * product-declared replacements), wired from the environment's
     * `minecraftDeclaredLibraries` configuration by env wiring.
     */
    @get:InputFiles
    @get:Classpath
    abstract val libraryJars: ConfigurableFileCollection
}

/**
 * `unimined:strip-bundled-libraries` — applies [BundledLibraryStrip]
 * to the environment artifact. Namespace-agnostic (entry-name
 * membership, not class content), so it needs no mappings, classpath,
 * or access model.
 */
class BundledLibraryStripStepType : RemapStepType<BundledLibraryStripStepSpec> {

    override val name = "unimined"
    override val stepName = "strip-bundled-libraries"
    override val specType: KClass<BundledLibraryStripStepSpec> =
        BundledLibraryStripStepSpec::class
    override val surfaces = setOf(RemapSurface.JAR)

    override fun supports(spec: RemapSpec) = true

    override fun create(
        spec: BundledLibraryStripStepSpec,
        ctx: StepContext
    ) = ArtifactStep { input, c ->
        val libs = spec.libraryJars.files.map { it.toPath() }
        if (libs.isEmpty()) return@ArtifactStep input
        // keep the input's filename — consumers key on it (FML's
        // matchFileName, launcher's jar-name heuristics)
        val out = Files.createDirectories(
            c.workDir.resolve("strip-bundled-libraries")
        ).resolve(input.fileName.toString())
        BundledLibraryStrip.strip(input, libs, out)
        out
    }
}
