package xyz.wagyourtail.unimined.adapter.expectplatform

import org.gradle.api.Project
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import xyz.wagyourtail.unimined.expect.ExpectPlatformExtension
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment

/**
 * Per-env `expectPlatform` DSL object — registered on each
 * environment by the adapter plugin when ExpectPlatform's plugin
 * is applied.
 *
 * [platform] is the per-env opt-in for ExpectPlatform dependency
 * transforms; it also seeds [platformName] so the agent + JAR
 * wiring activate for this env. `enabled`/`agent` are the opt-outs:
 * a child env that inherits [platformName] can exempt itself with
 * `enabled.set(false)`, and `agent.set(false)` drops the javaagent
 * from dev runs while keeping the static jar transform.
 */
abstract class ExpectPlatformEnvExtension(
    private val project: Project,
    private val env: MinecraftEnvironment
) {

    /** Master switch — off: no agent, no jar step for this env
     *  (configurations marked by [platform] stay marked). Default on. */
    @get:Input
    abstract val enabled: Property<Boolean>

    /**
     * Attach the ExpectPlatform javaagent + `-Dexpect.*` sysprops to
     * this env's runs — off: dev runs launch untransformed (useful
     * when the jar-side static transform suffices or when debugging
     * the transform itself). Default on.
     */
    @get:Input
    abstract val agent: Property<Boolean>

    /**
     * The ExpectPlatform platform name (`"fabric"`, `"forge"`, ...).
     * Conventions to the nearest inherited env's [platformName], so a
     * `common` env's declaration propagates to `combineWith` children;
     * set explicitly or via [platform].
     */
    @get:Optional
    @get:Input
    abstract val platformName: Property<String>

    /**
     * Annotation-descriptor remaps applied by the transform (ExpectPlatform's
     * `@Environment` → the loader's side annotation/enums) — **explicit
     * entries only**; the effective map consumed by transforms merges
     * the adapter's built-in loader table (fabric/quilt/forge-era/
     * neoforge/cleanroom) < the inherited env's map < these entries.
     */
    @get:Input
    abstract val remap: MapProperty<String, String>

    /** The merged table+inherited+explicit map — set by the env
     *  wiring; falls back to [remap] when unwired (standalone use). */
    @get:org.gradle.api.tasks.Internal
    internal var effectiveRemap:
            org.gradle.api.provider.Provider<Map<String, String>>? = null

    /**
     * Strip ExpectPlatform's own annotations from transformed output —
     * passed through to `TransformPlatform` and the artifact
     * transforms [platform] registers. Default off.
     */
    @get:Input
    abstract val stripAnnotations: Property<Boolean>

    /**
     * Declare this environment's platform — marks the env's
     * `modImplementation`/`modRuntimeOnly` configurations so resolved
     * mod jars are ExpectPlatform-transformed at dependency
     * resolution (ExpectPlatform's `platform(name, configuration)`
     * artifact transform), and sets [platformName]/[remap] for the
     * run + jar wiring.
     */
    @JvmOverloads
    fun platform(name: String, remap: Map<String, String> = emptyMap()) {
        platformName.set(name)
        this.remap.putAll(remap)
        val ep = project.extensions.getByType(ExpectPlatformExtension::class.java)
        listOf(
            env.modImplementationConfigurationName,
            env.modRuntimeOnlyConfigurationName
        ).mapNotNull { project.configurations.findByName(it) }
            .forEach { conf ->
                ep.platform(name, conf) {
                    stripAnnotations.convention(
                        this@ExpectPlatformEnvExtension.stripAnnotations)
                    this.remap.set(effectiveRemap
                        ?: this@ExpectPlatformEnvExtension.remap)
                }
            }
    }

}
