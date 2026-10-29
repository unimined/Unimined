package xyz.wagyourtail.unimined.remap.fakes

import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.*
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.reflect.KClass

/**
 * Spec for the test-only fake step — declared via
 * `surface.step(FakeStepSpec())` or resolved through ServiceLoader.
 */
class FakeStepSpec : StepSpec

/**
 * Copy the input jar adding a `fake-step.ran` marker entry — makes fake
 * pipeline runs observable end to end (tasks and artifact transforms).
 */
internal fun writeMarkedCopy(input: Path, out: Path) {
    JarOutputStream(out.toFile().outputStream()).use { dst ->
        JarFile(input.toFile()).use { src ->
            for (entry in src.entries()) {
                dst.putNextEntry(JarEntry(entry.name))
                src.getInputStream(entry).copyTo(dst)
                dst.closeEntry()
            }
        }
        dst.putNextEntry(JarEntry(FakeStep.MARKER))
        dst.write("ran".toByteArray())
        dst.closeEntry()
    }
}

/**
 * Test-only engine impl, registered through
 * `META-INF/services` on the test classpath so execution-time registries
 * discover it exactly like a real engine. Records each input it saw in
 * [calls] and produces a marked copy of the input jar.
 */
class FakeStep : RemapStepType<FakeStepSpec> {

    override val name = "fake"
    override val stepName = "fakestep"
    override val specType: KClass<FakeStepSpec> = FakeStepSpec::class
    override val surfaces: Set<RemapSurface> = RemapSurface.entries.toSet()
    override val needsAccessModel = true

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: FakeStepSpec, ctx: StepContext) = ArtifactStep { input, c ->
        calls.add(input)
        accessModels.add(c.accessModel)
        out(c, calls.size).also { writeMarkedCopy(input, it) }
    }

    internal fun out(ctx: StepContext, n: Int) = ctx.workDir.resolve("fake-$n.jar")

    companion object {

        const val MARKER = "fake-step.ran"

        val calls = mutableListOf<Path>()

        val accessModels = mutableListOf<xyz.wagyourtail.unimined.api.access.AccessModel?>()

        fun reset() {
            calls.clear()
            accessModels.clear()
        }

    }

}

/**
 * A second fake impl claiming the real `remap` verb — lets functional test
 * scripts declare `remap("fake-remap")` using the production
 * [RemapStepSpec] (which lives in the plugin jar, unlike test classes).
 */
class FakeRemapStep : RemapStepType<RemapStepSpec> {

    override val name = "fake-remap"
    override val stepName = "remap"
    override val specType: KClass<RemapStepSpec> = RemapStepSpec::class
    override val surfaces: Set<RemapSurface> = RemapSurface.entries.toSet()

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: RemapStepSpec, ctx: StepContext) = ArtifactStep { input, c ->
        val out = c.workDir.resolve("fake-remap.jar")
        writeMarkedCopy(input, out)
        out
    }

}
