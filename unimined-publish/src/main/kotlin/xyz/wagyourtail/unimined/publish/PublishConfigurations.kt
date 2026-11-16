package xyz.wagyourtail.unimined.publish

import org.gradle.api.tasks.SourceSet

/**
 * Names of the consumable `*Elements` configurations the publish
 * wiring creates per bound environment — the producer side of
 * `reobfArtifactConfiguration`-style in-build consumption.
 *
 * Dev-channel confs carry the dev-namespace artifacts under custom
 * `unimined-dev-*` usage values (the unprefixed helpers below); the
 * `dev*ElementsName` aliases make the channel explicit at the call
 * site. `<name>Prod*Elements` confs carry the production
 * (reobf-equivalent) jar under the jar's original classifier and the
 * conventional `java-api`/`java-runtime` usages. Access-document
 * conf names live on the environment (`accessTransformerConfName` /
 * `accessTransformerElementsConfName`).
 *
 * Prefer attribute-based selection over the `configuration =` escape
 * hatch: request `PublishAttributes.CHANNEL = "prod"` (plus
 * `ENVIRONMENT`/`NAMESPACE` to disambiguate) on the consumer's
 * resolvable configuration and Gradle picks the matching variant.
 */
object PublishConfigurations {

    /** Dev-channel api elements conf for [sourceSet] — the dev jar
     *  under the `unimined-dev-api` usage. */
    @JvmStatic
    fun apiElementsName(sourceSet: SourceSet): String =
        "${sourceSet.name}ApiElements"

    /** Explicit-channel alias of [apiElementsName]. */
    @JvmStatic
    fun devApiElementsName(sourceSet: SourceSet): String =
        apiElementsName(sourceSet)

    /** Dev-channel runtime elements conf for [sourceSet] — the dev
     *  jar under the `unimined-dev-runtime` usage. */
    @JvmStatic
    fun runtimeElementsName(sourceSet: SourceSet): String =
        "${sourceSet.name}RuntimeElements"

    /** Explicit-channel alias of [runtimeElementsName]. */
    @JvmStatic
    fun devRuntimeElementsName(sourceSet: SourceSet): String =
        runtimeElementsName(sourceSet)

    /** Dev-channel sources elements conf for [sourceSet] — the dev
     *  sources jar under the `unimined-dev-sources` usage. */
    @JvmStatic
    fun sourcesElementsName(sourceSet: SourceSet): String =
        "${sourceSet.name}SourcesElements"

    /** Explicit-channel alias of [sourcesElementsName]. */
    @JvmStatic
    fun devSourcesElementsName(sourceSet: SourceSet): String =
        sourcesElementsName(sourceSet)

    /** Production `java-api` elements conf for [sourceSet] — carries
     *  the `remap<Jar>` output under the `java-api` usage. Absent for
     *  identity-remap environments (dev == prod namespace — no prod
     *  channel is wired); when present, the component variant is
     *  still skipped unless a `remap<Jar>` producer registered. */
    @JvmStatic
    fun prodApiElementsName(sourceSet: SourceSet): String =
        "${sourceSet.name}ProdApiElements"

    /** Production `java-runtime` elements conf for [sourceSet] —
     *  same presence rules as [prodApiElementsName]. */
    @JvmStatic
    fun prodRuntimeElementsName(sourceSet: SourceSet): String =
        "${sourceSet.name}ProdRuntimeElements"

    /** Production sources elements conf for [sourceSet] — carries
     *  the `remap<SourcesJar>` output; same presence rules as
     *  [prodApiElementsName]. */
    @JvmStatic
    fun prodSourcesElementsName(sourceSet: SourceSet): String =
        "${sourceSet.name}ProdSourcesElements"

}
