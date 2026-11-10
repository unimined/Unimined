package xyz.wagyourtail.unimined.publish

import org.gradle.api.attributes.Attribute

/**
 * Variant attributes stamped on `unimined<Env>` component variants so
 * consumers can select dev/prod/sources/data artifacts (plan §11).
 *
 * Attribute values reflect *declared* environment metadata only —
 * namespace resolution stays lazy, so [NAMESPACE] is present only when
 * the env declares `devNamespace`/`modSourceNamespaces` explicitly.
 *
 * - [ENVIRONMENT] — environment id (`fabric`, `main`, ...)
 * - [LOADER] — loader family name (`fabric`, `forge`, ...) when declared
 * - [NAMESPACE] — the artifact's mapping namespace when declared
 * - [CHANNEL] — `"dev"` or `"prod"` (absent on data files)
 * - [ARTIFACT_ROLE] — `"jar"`, `"sources"`, `"javadoc"`, or `"data"`
 *
 * Dev-channel variants carry a custom [org.gradle.api.attributes.Usage]
 * value instead of `java-api`/`java-runtime` so ordinary consumers
 * resolve the production variants unambiguously; consumers that want a
 * dev artifact request the matching [USAGE_DEV_*] value explicitly:
 *
 * ```kotlin
 * dependencies {
 *     implementation("com.example:mymod-fabric:1.0") {
 *         attributes {
 *             attribute(Usage.USAGE_ATTRIBUTE,
 *                 objects.named(Usage::class.java, PublishAttributes.USAGE_DEV_API))
 *         }
 *     }
 * }
 * ```
 */
object PublishAttributes {

    val ENVIRONMENT: Attribute<String> =
        Attribute.of("unimined.environment", String::class.java)

    val LOADER: Attribute<String> =
        Attribute.of("unimined.loader", String::class.java)

    val NAMESPACE: Attribute<String> =
        Attribute.of("unimined.namespace", String::class.java)

    val CHANNEL: Attribute<String> =
        Attribute.of("unimined.channel", String::class.java)

    val ARTIFACT_ROLE: Attribute<String> =
        Attribute.of("unimined.artifactRole", String::class.java)

    const val CHANNEL_DEV = "dev"
    const val CHANNEL_PROD = "prod"

    const val ROLE_JAR = "jar"
    const val ROLE_SOURCES = "sources"
    const val ROLE_JAVADOC = "javadoc"
    const val ROLE_DATA = "data"

    /** Custom `org.gradle.usage` values on dev-channel variants — see
     *  class kdoc for the consumer-side selection snippet. */
    const val USAGE_DEV_API = "unimined-dev-api"
    const val USAGE_DEV_RUNTIME = "unimined-dev-runtime"
    const val USAGE_DEV_SOURCES = "unimined-dev-sources"
    const val USAGE_DEV_JAVADOC = "unimined-dev-javadoc"
    const val USAGE_DATA = "unimined-data"

}
