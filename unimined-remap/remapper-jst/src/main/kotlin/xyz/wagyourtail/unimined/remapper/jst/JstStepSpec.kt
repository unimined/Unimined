package xyz.wagyourtail.unimined.remapper.jst

import org.gradle.api.Action
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import xyz.wagyourtail.unimined.remap.RemapSurfaceSpec
import xyz.wagyourtail.unimined.remap.spi.StepSpec

/**
 * Spec for the `jst` step — wraps NeoForged's
 * [JavaSourceTransformer](https://github.com/neoforged/JavaSourceTransformer)
 * (`net.neoforged.jst:jst-cli`). JST pipelines its own transforms
 * (parchment mappings, access transformers, interface injection, unpick)
 * internally, so it's a single step rather than separate unimined steps.
 */
abstract class JstStepSpec : StepSpec {

    /**
     * Resolved classpath of the JST CLI tool — e.g. a detached
     * configuration resolving `net.neoforged.jst:jst-cli:<v>:all`.
     */
    @get:InputFiles
    @get:Classpath
    abstract val toolClasspath: ConfigurableFileCollection

    /**
     * JST transformations to enable — each becomes `--enable=<name>`
     * (e.g. `parchment`, `access-transformer`, `interface-injection`,
     * `unpick`).
     */
    @get:Input
    abstract val enable: ListProperty<String>

    /**
     * Additional CLI args forwarded verbatim — transform-specific inputs
     * like `--parchment-mappings <file>` or `--access-transformer <file>`
     * go here.
     */
    @get:Input
    abstract val extraArgs: ListProperty<String>

}

/**
 * Kotlin-DSL sugar: `sources { jst { enable.add("parchment") } }`.
 */
fun RemapSurfaceSpec.jst(action: Action<JstStepSpec> = Action {}): JstStepSpec =
    step(JstStepSpec::class.java, action)
