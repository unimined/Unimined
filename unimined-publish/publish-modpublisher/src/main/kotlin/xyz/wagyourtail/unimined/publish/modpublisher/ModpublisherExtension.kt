package xyz.wagyourtail.unimined.publish.modpublisher

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import javax.inject.Inject

/**
 * `uniminedPublishModpublisher { }` — upload destination config,
 * mirroring ModPublisher's own flat surface. The adapter feeds these
 * into `publisher { }` when `com.hypherionmc.modutils.modpublisher`
 * is applied.
 *
 * API keys are `Property<String>` providers — resolve them from
 * `providers.gradleProperty`/`providers.environmentVariable`; they
 * are only read at execution time and never logged.
 *
 * ```kotlin
 * uniminedPublishModpublisher {
 *     apiKeys {
 *         curseforge.set(providers.environmentVariable("CURSE_TOKEN"))
 *         modrinth.set(providers.environmentVariable("MODRINTH_TOKEN"))
 *     }
 *     curseID.set("1234"); modrinthID.set("abc")
 *     versionType.set("release")
 *     mergedArtifact.set(layout.buildDirectory.file("libs/merged.jar"))
 * }
 * ```
 */
abstract class ModpublisherExtension @Inject constructor(
    objects: ObjectFactory
) {

    /** Credential providers — read at execution time only. */
    val apiKeys: ApiKeys = objects.newInstance(ApiKeys::class.java)

    fun apiKeys(action: Action<ApiKeys>) = action.execute(apiKeys)

    /** Platform project ids (null/blank = platform skipped). */
    abstract val curseID: Property<String>
    abstract val modrinthID: Property<String>
    abstract val githubRepo: Property<String>

    /** Release metadata (mirrors ModPublisher's fields). */
    abstract val version: Property<String>
    abstract val versionType: Property<String>
    abstract val changelog: Property<String>
    abstract val displayName: Property<String>
    abstract val gameVersions: ListProperty<String>
    abstract val loaders: ListProperty<String>
    abstract val curseEnvironment: Property<String>

    /** ModPublisher debug mode — no files actually upload. */
    abstract val debug: Property<Boolean>

    /**
     * The loader-merge merged artifact (wins over
     * [perLoaderArtifacts] when present) — the publish host sets a
     * convention from `mergedElements` when the merge plugin applies.
     */
    abstract val mergedArtifact: RegularFileProperty

    /**
     * loader name → production (remapped) jar — used when no merged
     * artifact exists. Dev artifacts are never assignable here.
     */
    abstract val perLoaderArtifacts: MapProperty<String, RegularFile>

    /** Extra upload files (same semantics as `addAdditionalFile`). */
    abstract val additionalFiles: ListProperty<RegularFile>

    /**
     * Upload credentials — one optional token per platform.
     */
    abstract class ApiKeys {
        abstract val curseforge: Property<String>
        abstract val modrinth: Property<String>
        abstract val github: Property<String>
    }

}
