package xyz.wagyourtail.unimined.remapper.uml

import net.ornithemc.condor.Condor
import net.ornithemc.condor.Options
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * `condor` — Ornithe's local-variable-table generator
 * (`net.ornithemc.condor.Condor`), as wired by Ploceus:
 * `Condor.run(jar, libraries, Options.builder().removeInvalidEntries()
 * .keepParameterNames().build())` where `libraries` is the Minecraft
 * dependency libraries.
 *
 * Upstream regenerates the `LocalVariableTable` of every method whose
 * table is missing or *incomplete* (no `this` entry, or entries not
 * covering `maxLocals`) — frame analysis through a classpath-aware
 * `SimpleVerifier` tracks slot reuse, so one slot may yield several
 * range entries, typed per live range. Generated entries then feed the
 * namer: `this` at index 0, type-derived names (`i`, `j`, … / camelCase
 * simple class names) elsewhere, `MethodParameters` names copied onto
 * parameter entries under `keepParameterNames`, and the parameter
 * attribute re-synced to the final names. `removeInvalidEntries` strips
 * generated entries that reuse `this`/parameter slots (ProGuard slot
 * packing), including wide-type overlap slots.
 *
 * The `libraries` parameter is upstream's type-resolution classpath
 * (hierarchy walks in `isInterface`/common-superclass queries during
 * frame analysis); resolution tries the jar itself first, then the
 * libraries, then the JRE. The pipeline's classpath already carries
 * exactly the MC dependency libraries Ploceus passes — declaring
 * [needsClasspath] materializes it as [StepContext.classpath], so no
 * extra wiring is needed. Entries zipfs cannot mount (directories,
 * missing files) are dropped rather than crashing the run.
 *
 * Upstream condor rewrites the jar in place, while [ArtifactStep] must
 * not mutate its input — so this step first copies the artifact and
 * applies condor to the copy.
 */
class CondorStepType : RemapStepType<CondorStepSpec> {

    override val name = "ornithe-condor"
    override val stepName = "condor"
    override val specType = CondorStepSpec::class
    override val surfaces = setOf(RemapSurface.JAR, RemapSurface.DECOMPILE)

    /** Upstream type resolution wants the MC dependency libraries —
     *  materialize the pipeline classpath. */
    override val needsClasspath = true

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: CondorStepSpec, ctx: StepContext) = ArtifactStep { input, sctx ->
        val out = sctx.workDir.resolve(
            input.fileName.toString().removeSuffix(".jar") + "-condor.jar"
        )
        Files.copy(input, out, StandardCopyOption.REPLACE_EXISTING)
        // upstream mounts every library as a zip filesystem — only
        // regular files can mount; anything else would crash the run
        // rather than simply not participate in type resolution
        val libraries = sctx.classpath.filter { Files.isRegularFile(it) }
        synchronized(ZIPFS_LOCK) {
            Condor.run(out, libraries, OPTIONS)
        }
        out
    }

    private companion object {

        /** Ploceus's exact option set. */
        val OPTIONS: Options = Options.builder()
            .removeInvalidEntries()
            .keepParameterNames()
            .build()

        /**
         * JVM-global serialization for condor runs. Upstream mounts the
         * jar *and every library* through the JDK zipfs provider, which
         * keys open filesystems by real path per JVM — two parallel
         * artifact transforms sharing library jars (dev+prod pipelines,
         * or sibling environments) would otherwise race into
         * `FileSystemAlreadyExistsException`. Ploceus never sees this
         * (its jar processing is single-threaded); unimined's transforms
         * are parallel. Interned string literals are the same instance
         * JVM-wide, so the lock holds even if the plugin is loaded in
         * several classloaders — and runs are once per env artifact, so
         * serialization costs nothing. Cross-daemon access needs no
         * protection: libraries are only ever read.
         */
        val ZIPFS_LOCK = "xyz.wagyourtail.unimined.ornithe-condor.zipfs".intern()
    }
}
