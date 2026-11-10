package xyz.wagyourtail.unimined.jvmdg

import org.gradle.api.Action
import org.gradle.api.JavaVersion
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import xyz.wagyourtail.unimined.remap.RemapSurfaceSpec
import xyz.wagyourtail.unimined.remap.spi.StepSpec

/**
 * Spec for the `downgrade` step: rewrites class files to an older
 * class-file major version via JvmDowngrader, backporting newer JDK
 * API calls onto its `java-api` stub implementations.
 *
 * The step runs on the JAR surface — declared on the dev-artifact
 * pipeline it makes old-loader runtimes (FG-era ASM floors) able to
 * read modern-compiled classes; declared on a production/mod pipeline
 * it produces jars loadable on older JVMs.
 */
abstract class JvmDowngradeStepSpec : StepSpec {

    @get:Input
    @get:Optional
    override val engine: String?
        get() = null

    /**
     * Target Java release. Mapped to a class-file major as
     * `major + 44` (`VERSION_1_8` → 52). Overridden by [classVersion]
     * when both are set.
     */
    @get:Optional
    @get:Input
    abstract val target: Property<JavaVersion>

    /**
     * Raw class-file major version (`52` = Java 8). Wins over
     * [target] when set — use it for releases [JavaVersion] cannot
     * name (pre-1.5) or intermediate targets.
     */
    @get:Optional
    @get:Input
    abstract val classVersion: Property<Int>

    /**
     * The `jvmdowngrader-java-api` jar(s) — the stub implementation
     * surface downgraded calls are bound to. Populated by the plugin
     * with the published java-api artifact; override to pin a
     * different (e.g. locally-built) api jar.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val api: ConfigurableFileCollection

    /**
     * Shade the used java-api stub classes into the output jar
     * (jvmdg's `ApiShader`, `shadeInlining`) so a downgraded artifact
     * is self-contained — off, the produced jar needs `java-api` on
     * the classpath at runtime. Default true.
     */
    @get:Optional
    @get:Input
    abstract val shade: Property<Boolean>

    /**
     * Package prefix the shaded stubs land under (jvmdg's
     * `shadePath`). Default: the input artifact's base name,
     * sanitized, + `/` — e.g. `mymod-1.0/` — matching jvmdg's own
     * Gradle plugin so multiple shaded jars can't collide.
     */
    @get:Optional
    @get:Input
    abstract val shadePrefix: Property<String>

}

/**
 * Kotlin-DSL sugar: `jar { downgrade { target.set(JavaVersion.VERSION_1_8) } }`.
 */
fun RemapSurfaceSpec.downgrade(
    action: Action<JvmDowngradeStepSpec> = Action {}
): JvmDowngradeStepSpec = step(JvmDowngradeStepSpec::class.java, action)
