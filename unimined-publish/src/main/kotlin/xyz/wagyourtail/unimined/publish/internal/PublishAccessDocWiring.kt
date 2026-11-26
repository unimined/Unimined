package xyz.wagyourtail.unimined.publish.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Category
import org.gradle.api.component.AdhocComponentWithVariants
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.publish.PublishAttributes
import java.io.File

/**
 * Access-document variants (NG `AccessTransformerPublishing` /
 * MDG `DataFileCollections` parity, plan §4.1): the environment's own
 * access documents ride a `accesstransformer`-category consumable
 * configuration on the `unimined<Env>` component, so dependents can
 * pull them by module coordinate instead of jar-scanning:
 *
 * ```kotlin
 * dependencies {
 *     uniminedFabricAccessTransformer("com.example:parentmod:1.0")
 * }
 * ```
 *
 * - `unimined<Env>AccessTransformer` — resolvable + declarable;
 *   resolved docs join [MinecraftEnvironment.consumedAccessDocs], which
 *   the transform wirings append to the env's *dependency* class-tweaker
 *   channel (dep-authored docs bind like dep-jar docs — never the
 *   user's own headerless-dev-namespace binding, never shipped inside
 *   the project's own jar).
 * - `unimined<Env>AccessTransformerElements` — consumable, same
 *   category; artifacts are the env's generated canonical doc plus
 *   explicitly optioned `access { from(path) { } }`/`accessFile(path)`
 *   docs. The component variant self-skips when the artifact set is
 *   empty (MDG's `details.skip()` model), so environments without
 *   access declarations publish nothing.
 */
internal object PublishAccessDocWiring {

    fun wire(
        project: Project,
        environment: MinecraftEnvironment,
        component: AdhocComponentWithVariants
    ) {
        val category = project.objects.named(
            Category::class.java, PublishAttributes.CATEGORY_ACCESS_TRANSFORMER
        )

        val consume = project.configurations.maybeCreate(
            environment.accessTransformerConfName
        ).apply {
            isCanBeConsumed = false
            isCanBeResolved = true
            attributes.attribute(Category.CATEGORY_ATTRIBUTE, category)
        }
        environment.consumedAccessDocs.from(consume)

        val elements = project.configurations.maybeCreate(
            environment.accessTransformerElementsConfName
        ).apply {
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes.attribute(Category.CATEGORY_ATTRIBUTE, category)
            PublishVariantWiring.stampAttributes(
                attributes, project, environment,
                null, PublishAttributes.ROLE_ACCESS_DOCS, null
            )
        }

        // canonical doc artifacts are pushed from the transform side
        // (`EnvironmentClasspathWiring` wires a live-rule on
        // `accessTransformerElementsConfName` where the doc provider
        // is created) — enumerating `generatedAccessFiles` here would
        // race that plugin's afterEvaluate ordering

        // explicitly optioned docs — `accessFile(path) { }` lands in
        // `accessFileOverrides` during DSL eval; `access { from(path)
        // { } }` entries ride `namedFiles` on the access specs. Both
        // are DSL-time state, visible regardless of plugin ordering —
        // unioned and deduped by canonical path
        val declared = buildList {
            environment.accessFileOverrides.get().forEach { add(it.path) }
            environment.effectiveAccessSpecs()
                .flatMap { it.namedFiles }
                .mapNotNullTo(this) {
                    project.files(it.path).files.singleOrNull()?.absolutePath
                }
        }.mapTo(linkedSetOf()) { File(it).canonicalPath }
        declared.forEachIndexed { i, path ->
            elements.outgoing.artifact(File(path)) { a ->
                a.classifier = "access-transformer-${i + 1}"
            }
        }

        component.addVariantsFromConfiguration(elements) { details ->
            if (details.configurationVariant.artifacts.isEmpty()) {
                details.skip()
            }
        }
    }
}
