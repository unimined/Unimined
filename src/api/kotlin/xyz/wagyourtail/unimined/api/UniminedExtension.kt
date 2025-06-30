package xyz.wagyourtail.unimined.api

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.FlatDirectoryArtifactRepository
import org.gradle.api.tasks.SourceSet
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.ApiStatus.ScheduledForRemoval
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.api.minecraft.patch.fabric.FabricLikeApiExtension
import xyz.wagyourtail.unimined.api.source.task.MigrateMappingsTask
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.sourceSets
import java.nio.file.Path
import kotlin.io.path.createDirectories

val Project.unimined
    get() = extensions.getByType(UniminedExtension::class.java)

val Project.uniminedMaybe
    get() = extensions.findByType(UniminedExtension::class.java)

/**
 * the main entrypoint.
 *
 * usage:
 * ```groovy
 *
 * unimined.useGlobalCache = false
 *
 * unimined.minecraft {
 *     // See MinecraftConfig
 * }
 *
 * // optional second config for another sourceSet
 * unimined.minecraft(sourceSets.other) {
 *     // See MinecraftConfig
 * }
 * ```
 *
 * @see MinecraftConfig
 * @since 1.0.0
 */
@Suppress("OVERLOADS_ABSTRACT", "UNUSED")
abstract class UniminedExtension(project: Project) : FabricLikeApiExtension(project) {

    companion object {

        val pluginVersion: String = UniminedExtension::class.java.`package`.implementationVersion ?: "unknown"

        val isSnapshot = pluginVersion.endsWith("-SNAPSHOT")

        val snapshotVersion = if (isSnapshot) { pluginVersion.removeSuffix("-SNAPSHOT").substringAfterLast("-") } else ""

    }

    var useGlobalCache: Boolean by FinalizeOnRead(true)
    var forceReload: Boolean by FinalizeOnRead(project.properties["unimined.forceReload"] == "true" || project.gradle.startParameter.isRefreshDependencies)

    /**
     * VERY not recommended to disable
     */
    @set:ApiStatus.Experimental
    var footgunChecks: Boolean by FinalizeOnRead(true)

    @Deprecated("functions provided directly by unimined", ReplaceWith("unimined"))
    val fabricApi = project.extensions.create("fabricApi", FabricLikeApiExtension::class.java)

    private val sourceSets by lazy {
        project.sourceSets
    }

    @get:ApiStatus.Internal
    abstract val minecrafts: MutableMap<SourceSet, MinecraftConfig>

    @get:ApiStatus.Internal
    abstract val minecraftConfiguration: Map<SourceSet, MinecraftConfig.() -> Unit>

    /**
     * @since 1.0.0
     */
    @JvmOverloads
    abstract fun minecraft(
        sourceSet: SourceSet = sourceSets.getByName("main"),
        lateApply: Boolean = false,
        action: MinecraftConfig.() -> Unit
    )

    /**
     * @since 1.0.0
     */
    @JvmOverloads
    fun minecraft(
        vararg sourceSets: SourceSet,
        lateApply: Boolean = false,
        action: MinecraftConfig.() -> Unit
    ) {
        for (sourceSet in sourceSets) {
            minecraft(sourceSet, lateApply, action)
        }
    }

    /**
     * @since 1.0.0
     */
    @JvmOverloads
    fun minecraft(
        sourceSet: SourceSet = sourceSets.getByName("main"),
        lateApply: Boolean = false,
        @DelegatesTo(value = MinecraftConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        minecraft(sourceSet, lateApply) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * @since 1.0.0
     */
    @JvmOverloads
    fun minecraft(
        sourceSets: List<SourceSet>,
        lateApply: Boolean = false,
        @DelegatesTo(value = MinecraftConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        for (sourceSet in sourceSets) {
            minecraft(sourceSet, lateApply, action)
        }
    }

    /**
     * @since 1.3.5
     */
    @JvmOverloads
    abstract fun reIndev(
        sourceSet: SourceSet = sourceSets.getByName("main"),
        lateApply: Boolean = false,
        action: MinecraftConfig.() -> Unit
    )

    /**
     * @since 1.3.5
     */
    @JvmOverloads
    fun reIndev(
        vararg sourceSets: SourceSet,
        lateApply: Boolean = false,
        action: MinecraftConfig.() -> Unit
    ) {
        for (sourceSet in sourceSets) {
            reIndev(sourceSet, lateApply, action)
        }
    }

    /**
     * @since 1.3.5
     */
    @JvmOverloads
    fun reIndev(
        sourceSet: SourceSet = sourceSets.getByName("main"),
        lateApply: Boolean = false,
        @DelegatesTo(value = MinecraftConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        reIndev(sourceSet, lateApply) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * @since 1.3.5
     */
    @JvmOverloads
    fun reIndev(
        sourceSets: List<SourceSet>,
        lateApply: Boolean = false,
        @DelegatesTo(value = MinecraftConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        for (sourceSet in sourceSets) {
            reIndev(sourceSet, lateApply, action)
        }
    }

    /**
     * @since 1.4.0
     */
    @JvmOverloads
    abstract fun bta(
        sourceSet: SourceSet = sourceSets.getByName("main"),
        lateApply: Boolean = false,
        action: MinecraftConfig.() -> Unit
    )

    /**
     * @since 1.4.0
     */
    @JvmOverloads
    fun bta(
        sourceSet: SourceSet = sourceSets.getByName("main"),
        lateApply: Boolean = false,
        @DelegatesTo(value = MinecraftConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        bta(sourceSet, lateApply) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * @since 1.4.0
     */
    @JvmOverloads
    fun bta(
        sourceSets: List<SourceSet>,
        lateApply: Boolean = false,
        @DelegatesTo(value = MinecraftConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        for (sourceSet in sourceSets) {
            bta(sourceSet, lateApply, action)
        }
    }

    /**
     * @since 1.2.0
     */
    @JvmOverloads
    abstract fun migrateMappings(sourceSet: SourceSet = sourceSets.getByName("main"), action: MigrateMappingsTask.() -> Unit)

    /**
     * @since 1.2.0
     */
    fun migrateMappings(
        vararg sourceSets: SourceSet,
        action: MigrateMappingsTask.() -> Unit
    ) {
        for (sourceSet in sourceSets) {
            migrateMappings(sourceSet, action)
        }
    }

    /**
     * @since 1.2.0
     */
    @JvmOverloads
    fun migrateMappings(
        sourceSet: SourceSet = sourceSets.getByName("main"),
        @DelegatesTo(value = MigrateMappingsTask::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        migrateMappings(sourceSet) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * @since 1.2.0
     */
    fun migrateMappings(
        sourceSets: List<SourceSet>,
        @DelegatesTo(value = MigrateMappingsTask::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        for (sourceSet in sourceSets) {
            migrateMappings(sourceSet, action)
        }
    }

    @ApiStatus.Internal
    fun getLocalCache(): Path {
        return project.projectDir.toPath().resolve(".gradle").resolve("unimined").resolve("local").createDirectories()
    }

    @ApiStatus.Internal
    fun getLocalCache(sourceSet: SourceSet): Path {
        if (sourceSet.name == "main") return getLocalCache()
        return getLocalCache().resolve(sourceSet.name).createDirectories()
    }

    @ApiStatus.Internal
    fun getGlobalCache(): Path {
        return if (useGlobalCache) {
            project.gradle.gradleUserHomeDir.toPath().resolve("caches").resolve("unimined").createDirectories()
        } else {
            project.rootProject.projectDir.toPath().resolve(".gradle").resolve("unimined").createDirectories()
        }
    }

    abstract val modsRemapRepo: FlatDirectoryArtifactRepository

    abstract fun wagYourMaven(name: String)
    abstract fun minecraftForgeMaven()
    abstract fun neoForgedMaven()
    abstract fun cleanroomRepos()
    abstract fun fabricMaven()
    abstract fun quiltMaven()
    abstract fun legacyFabricMaven()
    abstract fun ornitheMaven()
    abstract fun glassLauncherMaven(name: String)
    abstract fun fox2codeMaven()
    abstract fun signalumMaven(name: String)
    abstract fun flintMaven(name: String)
    abstract fun spigot()
    abstract fun spongeMaven()

    abstract fun arcseekersMaven()
    abstract fun parchmentMaven()
    abstract fun mcphackersIvy()
    abstract fun wispForestMaven(name: String = "releases")
    abstract fun sleepingTownMaven()

    abstract fun modrinthMaven()
    abstract fun curseMaven(beta: Boolean = false)
    abstract fun sonatypeStaging()
    abstract fun jitpack()

    abstract fun legacyLiteloaderMaven()
    abstract fun liteloaderMaven()

    abstract fun paperMaven()

    @ScheduledForRemoval
    @Deprecated("Use glassLauncherMaven(\"babric\") instead", ReplaceWith("glassLauncherMaven(\"babric\")"))
    fun babricMaven() = glassLauncherMaven("babric")

    @ScheduledForRemoval
    @Deprecated("use arcseekersMaven() instead", ReplaceWith("arcseekersMaven()"))
    fun outlandsMaven() = arcseekersMaven()
}
