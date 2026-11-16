package xyz.wagyourtail.unimined.publish

import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.Action
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConsumableConfiguration
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.component.ConfigurationVariantDetails
import org.gradle.api.provider.Provider
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.tasks.Jar
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.publish.internal.PublishVariantWiring

/**
 * Plan §11 variant wiring: non-`main` environments get their own
 * consumable `*Elements` configurations (the java plugin only creates
 * them for `main`), prod variants mirror the dev dependency view on
 * `remap<Jar>` outputs, identity environments publish one artifact,
 * and the `publish*` toggles gate each channel.
 *
 * Tests drive the real plugin path: `project.evaluate()` fires
 * `afterDslEval`, which runs [PublishVariantWiring.wireVariants] per
 * bound env — state that shapes wiring (env toggles, namespaces,
 * stub remap tasks) must be in place before evaluation.
 */
class PublishVariantWiringTest {

    private fun project(): ProjectInternal {
        val p = ProjectBuilder.builder().build() as ProjectInternal
        p.pluginManager.apply("java")
        p.pluginManager.apply(
            xyz.wagyourtail.unimined.minecraft.GradlePlugin::class.java)
        p.plugins.apply(GradlePlugin::class.java)
        return p
    }

    private class Bound(
        val env: MinecraftEnvironment,
        val sourceSet: SourceSet,
        val component: AdhocComponentWithVariants,
        val envPub: PublishExtension.EnvironmentPublication
    )

    /** Bind [envId] through the real plugin path — `evaluate()` fires
     *  `afterDslEval`, which runs the variant wiring — and return the
     *  pieces assertions need. [preEval] mutates env/publish state
     *  before evaluation so wiring sees it. */
    private fun bound(
        p: ProjectInternal, envId: String,
        preEval: (ProjectInternal) -> Unit = {}
    ): Bound {
        val minecraft = p.extensions.getByType(MinecraftExtension::class.java)
        if (envId != "main") minecraft.sourceSet(envId) {}
        preEval(p)
        p.evaluate()
        val env = minecraft.environments.getByName(envId)
        val sourceSet = p.extensions.getByType(SourceSetContainer::class.java)
            .getByName(envId)
        val componentName = if (envId == "main") "uniminedMain"
        else "unimined${envId.replaceFirstChar(Char::uppercase)}"
        val component = p.components.findByName(componentName)
            as AdhocComponentWithVariants
        val envPub = p.extensions.getByType(PublishExtension::class.java)
            .environments[envId]!!
        return Bound(env, sourceSet, component, envPub)
    }

    private fun conf(p: ProjectInternal, name: String) =
        p.configurations.findByName(name)

    /** Attribute key names on a conf — getAttribute() is unusable on
     *  wired confs because it realizes the lazy jvm-version provider
     *  (requiredJavaVersion → the Minecraft pipeline, unresolvable in
     *  this fixture). Plain-value assertions live in the
     *  stampConfAttributes test on a provider-free scratch conf. */
    private fun attributeKeys(conf: org.gradle.api.artifacts.Configuration) =
        conf.attributes.keySet().map { it.name }.toSet()

    @Test
    fun `non-main env gets consumable dev configurations`() {
        val p = project()
        val b = bound(p, "fabric")
        val api = conf(p, "fabricApiElements")
        assertNotNull(api, "fabricApiElements must be created")
        api!!
        assertTrue(api.isCanBeConsumed)
        assertFalse(api.isCanBeResolved)
        // dev confs carry the dev usage directly — conf attributes ARE
        // the published variant attributes (stamped eagerly; the
        // addVariantsFromConfiguration details action runs after
        // consumable confs are locked and cannot mutate them)
        val apiKeys = attributeKeys(api)
        assertTrue(apiKeys.contains("org.gradle.usage"))
        assertTrue(apiKeys.contains("org.gradle.category"))
        assertTrue(apiKeys.contains("unimined.environment"))
        assertTrue(apiKeys.contains("unimined.channel"))
        assertTrue(apiKeys.contains("org.gradle.jvm.version"))
        assertTrue(
            api.extendsFrom.any { it.name == "fabricImplementation" },
            "fabricApiElements should extend fabricImplementation"
        )
        assertTrue(api.outgoing.artifacts.isNotEmpty(),
            "fabricApiElements should carry the fabricJar artifact")

        val sources = conf(p, "fabricSourcesElements")
        assertNotNull(sources, "fabricSourcesElements must be created")
        sources!!
        assertTrue(attributeKeys(sources).contains("org.gradle.docstype"))
        assertTrue(sources.outgoing.artifacts.isNotEmpty())
    }

    @Test
    fun `prod variants mirror dev configurations when remap tasks exist`() {
        val p = project()
        // the transform module owns real remap tasks; stub them here so
        // the prod wiring has something to attach to
        p.tasks.register("remapFabricJar", Jar::class.java)
        p.tasks.register("remapFabricSourcesJar", Jar::class.java)
        val b = bound(p, "fabric")
        for (name in listOf(
            "fabricProdApiElements", "fabricProdRuntimeElements",
            "fabricProdSourcesElements"
        )) {
            val prod = conf(p, name)
            assertNotNull(prod, "$name must be created")
            prod!!
            assertTrue(prod.isCanBeConsumed)
            assertFalse(prod.isCanBeResolved)
            assertTrue(prod.outgoing.artifacts.isNotEmpty(),
                "$name should carry the remap artifact")
        }
        // prod api variant mirrors the dev api dependency view
        val prodApi = conf(p, "fabricProdApiElements")!!
        assertTrue(prodApi.extendsFrom.any { it.name == "fabricImplementation" })
        assertTrue(attributeKeys(prodApi).contains("org.gradle.usage"))
        assertTrue(attributeKeys(prodApi).contains("unimined.channel"))
    }

    @Test
    fun `publishProduction false skips prod configurations`() {
        val p = project()
        val ext = p.extensions.getByType(PublishExtension::class.java)
        ext.environment("fabric") { it.publishProduction = false }
        p.tasks.register("remapFabricJar", Jar::class.java)
        p.tasks.register("remapFabricSourcesJar", Jar::class.java)
        val b = bound(p, "fabric")
        assertNull(conf(p, "fabricProdApiElements"))
        assertNotNull(conf(p, "fabricApiElements"),
            "dev variants are unaffected by publishProduction")
    }

    @Test
    fun `identity env skips prod variants entirely`() {
        val p = project()
        p.tasks.register("remapFabricJar", Jar::class.java)
        p.tasks.register("remapFabricSourcesJar", Jar::class.java)
        // declared-equal dev/prod namespaces — the identity case the
        // remap pipeline resolves lazily; declaring them before
        // evaluation lets wiring skip prod without resolving mappings
        bound(p, "fabric") { proj ->
            proj.extensions.getByType(MinecraftExtension::class.java)
                .environments.named("fabric") { e ->
                    e.devNamespace.set("named")
                    e.modSourceNamespaces.set(listOf("named"))
                }
        }
        assertNull(conf(p, "fabricProdApiElements"),
            "identity envs publish one artifact — no prod variants")
        assertNull(conf(p, "fabricProdRuntimeElements"))
        assertNull(conf(p, "fabricProdSourcesElements"))
        assertNotNull(conf(p, "fabricApiElements"))
    }

    @Test
    fun `main env gets prefixed confs and java confs stay untouched`() {
        val p = project()
        p.tasks.register("remapJar", Jar::class.java)
        p.tasks.register("remapSourcesJar", Jar::class.java)
        val b = bound(p, "main")
        // unimined confs are prefixed per source set even for main —
        // java's apiElements/runtimeElements are shared with
        // components.java and must never carry dev usages or
        // unimined.* stamps (the javaPublication opt-in depends on it)
        val api = conf(p, "mainApiElements")
        assertNotNull(api, "mainApiElements must be created")
        api!!
        assertTrue(api.isCanBeConsumed)
        assertFalse(api.isCanBeResolved)
        val apiKeys = attributeKeys(api)
        assertTrue(apiKeys.contains("org.gradle.usage"))
        assertTrue(apiKeys.contains("unimined.environment"))
        assertTrue(api.outgoing.artifacts.isNotEmpty(),
            "mainApiElements should carry the jar artifact")

        val javaApi = conf(p, "apiElements")
        assertNotNull(javaApi, "java plugin still owns apiElements")
        // keySet only — getAttribute would realize the lazy jvm-version
        // provider and resolve the Minecraft pipeline
        val javaApiKeys = javaApi!!.attributes.keySet().map { it.name }.toSet()
        assertFalse(javaApiKeys.contains("unimined.environment"),
            "components.java confs must not carry unimined stamps")

        val sources = conf(p, "mainSourcesElements")
        assertNotNull(sources, "mainSourcesElements must be created for main")
        sources!!
        assertTrue(attributeKeys(sources).contains("org.gradle.docstype"))
        assertNotNull(p.tasks.findByName("sourcesJar"),
            "a minimal sourcesJar fallback should be registered")

        assertNotNull(conf(p, "mainProdApiElements"))
        assertNotNull(conf(p, "mainProdRuntimeElements"))
        assertNotNull(conf(p, "mainProdSourcesElements"))
    }

    @Test
    fun `publishDev false leaves dev confs but publishProduction unaffected`() {
        val p = project()
        val ext = p.extensions.getByType(PublishExtension::class.java)
        ext.environment("fabric") { it.publishDev = false }
        p.tasks.register("remapFabricJar", Jar::class.java)
        p.tasks.register("remapFabricSourcesJar", Jar::class.java)
        val b = bound(p, "fabric")
        // confs are still created — they double as the prod templates
        // and project-dependency targets; only the component variants
        // are gated, which the afterDslEval wiring owns
        assertNotNull(conf(p, "fabricApiElements"))
        assertNotNull(conf(p, "fabricProdApiElements"),
            "publishDev=false must not gate the prod channel")
    }

    @Test
    fun `data files variant is skipped when no data conf exists`() {
        val p = project()
        val b = bound(p, "fabric")
        assertNull(conf(p, "fabricDataElements"),
            "wiring must not invent a data conf — a producer creates it")
    }

    /** Records which configurations were added as component
     *  variants — reading `usages` on the real component realizes
     *  outgoing artifact providers (the Minecraft pipeline), so the
     *  gate is asserted at the `addVariantsFromConfiguration` call. */
    @xyz.wagyourtail.unimined.api.util.NoGroovyTwin(
        "test-only recording fake — implements the Gradle component " +
                "interface, not a DSL surface"
    )
    private class RecordingComponent : AdhocComponentWithVariants {
        val variantConfs = mutableListOf<String>()
        override fun getName() = "recording"
        override fun addVariantsFromConfiguration(
            outgoing: Configuration,
            action: Action<in ConfigurationVariantDetails>
        ) {
            variantConfs += outgoing.name
        }

        override fun addVariantsFromConfiguration(
            outgoing: Provider<ConsumableConfiguration>,
            action: Action<in ConfigurationVariantDetails>
        ) {
            // provider form is not exercised here; recording the
            // provider itself would realize it
            variantConfs += "<provider>"
        }

        override fun withVariantsFromConfiguration(
            outgoing: Configuration,
            action: Action<in ConfigurationVariantDetails>
        ) {
            variantConfs += outgoing.name
        }

        override fun withVariantsFromConfiguration(
            outgoing: Provider<ConsumableConfiguration>,
            action: Action<in ConfigurationVariantDetails>
        ) {
            variantConfs += "<provider>"
        }
    }

    /** Drive [PublishVariantWiring.wireVariants] directly — the
     *  variant-realizing assertions in other tests go through the
     *  plugin path, but the publishDataFiles gate only needs the
     *  `addVariantsFromConfiguration` call set, which a recording
     *  component captures without touching artifact providers. */
    private fun wiredVariants(
        p: ProjectInternal,
        envPub: PublishExtension.EnvironmentPublication
    ): RecordingComponent {
        val env = p.extensions.getByType(MinecraftExtension::class.java)
            .environments.getByName("fabric")
        val sourceSet = p.extensions.getByType(SourceSetContainer::class.java)
            .getByName("fabric")
        val component = RecordingComponent()
        PublishVariantWiring.wireVariants(p, env, sourceSet, envPub, component)
        return component
    }

    @Test
    fun `publishDataFiles false gates the data variant off the component`() {
        val p = project()
        val ext = p.extensions.getByType(PublishExtension::class.java)
        p.extensions.getByType(MinecraftExtension::class.java)
            .sourceSet("fabric") {}
        ext.environment("fabric") { it.publishDataFiles = false }
        val envPub = ext.environments["fabric"]!!
        p.configurations.maybeCreate("fabricDataElements").apply {
            isCanBeConsumed = true
            isCanBeResolved = false
        }
        val component = wiredVariants(p, envPub)
        assertFalse(
            "fabricDataElements" in component.variantConfs,
            "publishDataFiles=false must skip the data variant: " +
                    component.variantConfs
        )
        assertTrue(
            component.variantConfs.isNotEmpty(),
            "dev/prod variants are unaffected by publishDataFiles"
        )
    }

    @Test
    fun `publishDataFiles default contributes the data variant`() {
        val p = project()
        val ext = p.extensions.getByType(PublishExtension::class.java)
        p.extensions.getByType(MinecraftExtension::class.java)
            .sourceSet("fabric") {}
        ext.environment("fabric") {}
        val envPub = ext.environments["fabric"]!!
        p.configurations.maybeCreate("fabricDataElements").apply {
            isCanBeConsumed = true
            isCanBeResolved = false
        }
        val component = wiredVariants(p, envPub)
        assertTrue(
            "fabricDataElements" in component.variantConfs,
            "the data conf becomes a component variant by default: " +
                    component.variantConfs
        )
    }

    @Test
    fun `existing data conf is left for variant wiring`() {
        val p = project()
        val b = bound(p, "fabric")
        // a producer (future AW/AT exports) creates the conf itself
        p.configurations.maybeCreate("fabricDataElements").apply {
            isCanBeConsumed = true
            isCanBeResolved = false
        }
        val data = conf(p, "fabricDataElements")
        assertNotNull(data)
        assertTrue(data!!.isCanBeConsumed)
    }

    @Test
    fun `variant stamps carry unimined metadata keys`() {
        // stampAttributes is what wireVariants applies eagerly to each
        // consumable conf — drive it directly on a scratch container.
        // Only key names are asserted: getAttribute() realizes the whole
        // lazy attribute map, which would resolve requiredJavaVersion →
        // the Minecraft pipeline (no version in this fixture).
        val p = project()
        val b = bound(p, "fabric")
        val attrs = p.configurations.maybeCreate("variantAttrs").attributes
        PublishVariantWiring.stampAttributes(
            attrs, p, b.env, PublishAttributes.CHANNEL_DEV,
            PublishAttributes.ROLE_JAR, "named", PublishAttributes.USAGE_DEV_API
        )
        val names = attrs.keySet().map { it.name }.toSet()
        assertTrue(names.containsAll(setOf(
            "unimined.environment", "unimined.channel",
            "unimined.artifactRole", "unimined.namespace",
            "org.gradle.usage", "org.gradle.jvm.version"
        )), "stamped attributes: $names")
    }

    @Test
    fun `stampConfAttributes sets plain consumable attribute values`() {
        // value-level check on a scratch conf — stampConfAttributes
        // only writes plain values (the lazy jvm-version provider is
        // added separately by stampAttributes), so getAttribute is
        // safe here
        val p = project()
        val api = p.configurations.maybeCreate("scratchApi")
        PublishVariantWiring.stampConfAttributes(
            p, api, PublishAttributes.USAGE_DEV_API, null)
        assertEquals(
            PublishAttributes.USAGE_DEV_API,
            api.attributes.getAttribute(Usage.USAGE_ATTRIBUTE)?.toString())
        assertEquals(
            Category.LIBRARY,
            api.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.toString())
        assertEquals(
            LibraryElements.JAR,
            api.attributes.getAttribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE)?.toString())

        val sources = p.configurations.maybeCreate("scratchSources")
        PublishVariantWiring.stampConfAttributes(
            p, sources, PublishAttributes.USAGE_DEV_SOURCES, DocsType.SOURCES)
        assertEquals(
            DocsType.SOURCES,
            sources.attributes.getAttribute(
                DocsType.DOCS_TYPE_ATTRIBUTE)?.toString())
        assertEquals(
            Category.DOCUMENTATION,
            sources.attributes.getAttribute(
                Category.CATEGORY_ATTRIBUTE)?.toString())
    }

    @Test
    fun `access doc conf pair feeds the env's dep-doc channel`() {
        val p = project()
        val declared = java.io.File.createTempFile("mod", ".cfg").apply {
            writeText("accessible\tfield\tnet/minecraft/Example\tf_0\tI\n")
            deleteOnExit()
        }
        val b = bound(p, "fabric") { proj ->
            val env = proj.extensions.getByType(MinecraftExtension::class.java)
                .environments.getByName("fabric")
            env.accessFile(declared) {}
        }

        // consumer: resolvable, category-stamped — resolved docs feed
        // the env's dep-doc channel, not the jar-shipping collections
        val consume = conf(p, "uniminedFabricAccessTransformer")
        assertNotNull(consume, "consume conf must be created")
        consume!!
        assertTrue(consume.isCanBeResolved)
        assertFalse(consume.isCanBeConsumed)
        assertEquals(
            "accesstransformer",
            consume.attributes.getAttribute(
                Category.CATEGORY_ATTRIBUTE)?.toString())
        assertTrue(b.env.consumedAccessDocs.from.contains(consume),
            "consumedAccessDocs must pull from the consume conf")

        // producer: consumable elements conf carries the declared doc —
        // getAttribute() is unusable here (realizes the lazy
        // jvm-version provider → the Minecraft pipeline); the category
        // value is asserted on the consume conf above, which carries
        // only plain attributes
        val elements = conf(p, "uniminedFabricAccessTransformerElements")
        assertNotNull(elements, "elements conf must be created")
        elements!!
        assertTrue(elements.isCanBeConsumed)
        assertFalse(elements.isCanBeResolved)
        assertTrue(attributeKeys(elements).contains("org.gradle.category"))
        assertTrue(attributeKeys(elements).contains("unimined.environment"))
        assertTrue(
            elements.outgoing.artifacts.any {
                it.classifier == "access-transformer-1"
            },
            "declared accessFile must publish as access-transformer-1")
    }

    @Test
    fun `env without access docs gets empty elements conf`() {
        val p = project()
        bound(p, "fabric")
        val elements = conf(p, "uniminedFabricAccessTransformerElements")
        assertNotNull(elements)
        assertTrue(elements!!.outgoing.artifacts.isEmpty(),
            "no access declarations → no artifacts → variant skips")
    }

}
