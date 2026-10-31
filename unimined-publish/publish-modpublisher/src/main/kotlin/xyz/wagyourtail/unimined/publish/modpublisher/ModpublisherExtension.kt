package xyz.wagyourtail.unimined.publish.modpublisher

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * `uniminedPublishModpublisher { }` — platform upload configuration.
 * API keys flow through Gradle providers (`providers.gradleProperty`
 * / `providers.environmentVariable`) — they are never logged or
 * embedded in task outputs.
 */
abstract class ModpublisherExtension @Inject constructor(
    private val objects: ObjectFactory
) {

    private fun platform(name: String) =
        objects.newInstance(PlatformTarget::class.java, name)

    /** CurseForge upload block. */
    fun curseforge(action: Action<PlatformTarget>) =
        action.execute(platformsInternal.getOrPut("curseforge") {
            platform("curseforge")
        })

    /** Modrinth upload block. */
    fun modrinth(action: Action<PlatformTarget>) =
        action.execute(platformsInternal.getOrPut("modrinth") {
            platform("modrinth")
        })

    /** GitHub release upload block. */
    fun github(action: Action<PlatformTarget>) =
        action.execute(platformsInternal.getOrPut("github") {
            platform("github")
        })

    internal val platformsInternal =
        linkedMapOf<String, PlatformTarget>()

    /**
     * One upload destination — project id, release metadata, and the
     * credential provider (must resolve at execution time only).
     */
    abstract class PlatformTarget @Inject constructor(val name: String) {
        abstract val projectId: Property<String>
        abstract val apiKey: Property<String>
        abstract val versionName: Property<String>
        abstract val releaseType: Property<String>
        abstract val gameVersions: org.gradle.api.provider.ListProperty<String>
    }

}
