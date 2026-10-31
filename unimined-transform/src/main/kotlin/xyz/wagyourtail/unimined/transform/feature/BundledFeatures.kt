package xyz.wagyourtail.unimined.transform.feature

/**
 * Bundled [EnvironmentFeature] impls — the stock capability-binding
 * vocabulary (`mixin`, `accessWidener`, `nestedJars`, `modDiscovery`,
 * …) loaders declare and `custom` resolves. Wiring each impl owns
 * (classpath content, run args, prod repack) is consumed by the
 * env engine — these declarations carry validation + surface data.
 */

object MixinFeature : EnvironmentFeature {
    override val id = "mixin"
    override val surfaces = setOf(
        EnvironmentFeature.Surface.CLASSPATH,
        EnvironmentFeature.Surface.RUN_CONFIG
    )
}

object MixinExtrasFeature : EnvironmentFeature {
    override val id = "mixinExtras"
    override val surfaces = setOf(EnvironmentFeature.Surface.CLASSPATH)
}

object AccessTransformerFeature : EnvironmentFeature {
    override val id = "accessTransformer"
    override val surfaces = setOf(
        EnvironmentFeature.Surface.CLASSPATH,
        EnvironmentFeature.Surface.PROD_REPACK
    )
}

object AccessWidenerFeature : EnvironmentFeature {
    override val id = "accessWidener"
    override val surfaces = setOf(
        EnvironmentFeature.Surface.CLASSPATH,
        EnvironmentFeature.Surface.PROD_REPACK
    )
}

object ClassTweakerFeature : EnvironmentFeature {
    override val id = "classTweaker"
    override val surfaces = setOf(
        EnvironmentFeature.Surface.CLASSPATH,
        EnvironmentFeature.Surface.RUN_CONFIG
    )
}

object InterfaceInjectionFeature : EnvironmentFeature {
    override val id = "interfaceInjection"
    override val surfaces = setOf(EnvironmentFeature.Surface.COMPILE)
}

/**
 * `nestedJars("fabric"|"jarjar")` — dev-classpath extraction +
 * prod repack format; `jarjar` replicates best-satisfying-candidate
 * range selection so dev classpath can't diverge from prod.
 */
object NestedJarsFeature : EnvironmentFeature {
    override val id = "nestedJars"
    override val surfaces = setOf(
        EnvironmentFeature.Surface.CLASSPATH,
        EnvironmentFeature.Surface.PROD_REPACK
    )

    private val formats = setOf("fabric", "jarjar")

    override fun applies(context: EnvironmentFeature.FeatureContext): Boolean =
        context.bindingValue == null || context.bindingValue in formats
}

/**
 * `modDiscovery("classpath"|"dli"|"modClasses")` — how the runtime
 * finds dev mods. A custom loader row declaring no discovery binding
 * fails at selection rather than running silently mod-less.
 */
object ModDiscoveryFeature : EnvironmentFeature {
    override val id = "modDiscovery"
    override val surfaces = setOf(EnvironmentFeature.Surface.RUN_CONFIG)

    private val mechanisms = setOf("classpath", "dli", "modClasses")

    override fun applies(context: EnvironmentFeature.FeatureContext): Boolean =
        context.bindingValue in mechanisms
}

object JavaAgentsFeature : EnvironmentFeature {
    override val id = "javaAgents"
    override val surfaces = setOf(EnvironmentFeature.Surface.RUN_CONFIG)
}

object TweakClassesFeature : EnvironmentFeature {
    override val id = "tweakClasses"
    override val surfaces = setOf(EnvironmentFeature.Surface.RUN_CONFIG)
}

object MainClassFeature : EnvironmentFeature {
    override val id = "mainClass"
    override val surfaces = setOf(EnvironmentFeature.Surface.RUN_CONFIG)
}

object JavaVersionFeature : EnvironmentFeature {
    override val id = "javaVersion"
    override val surfaces = setOf(
        EnvironmentFeature.Surface.COMPILE,
        EnvironmentFeature.Surface.RUN_CONFIG
    )
}

/** ServiceLoader source — the stock vocabulary, bundled in core. */
class BundledFeatureSource : FeatureSource {

    override fun features(): List<EnvironmentFeature> = listOf(
        MixinFeature,
        MixinExtrasFeature,
        AccessTransformerFeature,
        AccessWidenerFeature,
        ClassTweakerFeature,
        InterfaceInjectionFeature,
        NestedJarsFeature,
        ModDiscoveryFeature,
        JavaAgentsFeature,
        TweakClassesFeature,
        MainClassFeature,
        JavaVersionFeature,
    )

}
