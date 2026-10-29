package xyz.wagyourtail.unimined.runs

import org.gradle.api.Named
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.SourceSet
import javax.inject.Inject

/** Which side a run targets. */
enum class RunSide { CLIENT, SERVER }

/**
 * One named run configuration — materialized into a `run<Name>` task.
 * Un-set fields (main class, args, classpath, natives) default from the
 * environment's launch plan at execution time.
 */
abstract class RunConfig @Inject constructor(
    private val name: String,
    objects: ObjectFactory
) : Named {

    override fun getName(): String = name

    /** The environment (`SourceSet` name) this run launches. */
    abstract val environment: Property<String>

    abstract val side: Property<RunSide>

    /** Working directory — defaults to `run/<environment>-<name>`. */
    abstract val workingDirectory: Property<String>

    /** Main-class override — defaults from launch metadata + mutators. */
    abstract val mainClass: Property<String>

    /** Extra JVM args appended after the version document's. */
    abstract val jvmArgs: ListProperty<String>

    /** Extra program args appended after the version document's. */
    abstract val programArgs: ListProperty<String>

    /** Extra environment variables for the launched process. */
    abstract val environmentVariables: MapProperty<String, String>

    /**
     * Data piped to the launched process's stdin. For dedicated
     * servers, console commands read from stdin queue until the server
     * ticks — so `stdin.set("stop\n")` boots the server and
     * auto-stops it right after "Done" (test/smoke-launch automation).
     */
    abstract val stdin: Property<String>

    /** Offline-mode player name — `auth(offline(name))` for now. */
    abstract val username: Property<String>

    /** Rule features to enable (`is_demo_user`, `has_custom_resolution`). */
    abstract val features: SetProperty<String>

    /** Optional window resolution forwarded as `--width`. */
    abstract val width: Property<Int>

    /** Optional window resolution forwarded as `--height`. */
    abstract val height: Property<Int>

    /** Download asset objects for the launch's asset index (default on). */
    abstract val downloadAssets: Property<Boolean>

    init {
        environment.convention(SourceSet.MAIN_SOURCE_SET_NAME)
        val defaultSide =
            if (name.equals("server", ignoreCase = true)) RunSide.SERVER else RunSide.CLIENT
        side.convention(defaultSide)
        // no mainClass convention — absent until metadata/mutators fill it
        jvmArgs.convention(emptyList())
        // dedicated servers default headless; opt out via `programArgs.set(...)`
        programArgs.convention(
            if (defaultSide == RunSide.SERVER) listOf("nogui") else emptyList()
        )
        environmentVariables.convention(emptyMap())
        username.convention("Dev")
        features.convention(emptySet())
        downloadAssets.convention(true)
    }

}
