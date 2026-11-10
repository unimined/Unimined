package xyz.wagyourtail.unimined.publish.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.SourceSet
import org.gradle.jvm.tasks.Jar
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.publish.PublishAttributes

/**
 * Dev-channel variant wiring — the `*ApiElements`/`*RuntimeElements`/
 * `*SourcesElements` consumable configurations that mirror the java
 * plugin's outgoing variants but carry dev-namespace artifacts and
 * unimined's own usage attributes. Extracted from
 * [PublishVariantWiring]: the dev-channel configuration layer
 * ([DevConf] model, spec table, conf creation, dev-only naming/bucket
 * helpers) is a distinct concept from prod-channel remap wiring and
 * component lifecycle.
 */
internal object PublishDevVariantWiring {

    class DevConf(
        val confName: String,
        val depBuckets: List<String>,
        /** task producing the artifact; null → conf carries no artifact. */
        val artifactTaskName: String?,
        /** register a minimal sources jar when [artifactTaskName] is
         *  absent (the transform module normally registers it). */
        val registerArtifactIfMissing: Boolean = false,
        /** [DocsType] value for documentation confs, null for library. */
        val docType: String?,
        val role: String,
        /** custom usage stamped on the conf itself (dev channel). */
        val devUsage: String,
        /** Maven classifier for the artifact — dev-channel files must
         *  not collide with the prod (null-classifier) main artifact;
         *  null leaves the artifact as the publication's main file
         *  (the only-channel case: identity envs, no remap pipeline). */
        val artifactClassifier: String?
    )

    /**
     * Ensure the dev-channel outgoing configurations exist and return
     * `confName -> spec` for every one that does.
     */
    fun ensureDevConfigurations(
        project: Project,
        environment: MinecraftEnvironment,
        sourceSet: SourceSet,
        prodJar: Boolean,
        prodSources: Boolean
    ): Map<String, DevConf> {
        val result = linkedMapOf<String, DevConf>()
        for (spec in devSpecs(environment, sourceSet, prodJar, prodSources)) {
            val conf = ensureDevConf(project, spec, sourceSet) ?: continue
            result[spec.confName] = spec
        }
        return result
    }

    private fun devSpecs(
        environment: MinecraftEnvironment,
        sourceSet: SourceSet,
        prodJar: Boolean,
        prodSources: Boolean
    ): List<DevConf> {
        val jarTask = sourceSet.jarTaskName
        val sourcesTask = sourceSet.sourcesJarTaskName
        return listOf(
            DevConf(
                confName = apiElementsName(sourceSet),
                depBuckets = apiDepBuckets(environment, sourceSet),
                artifactTaskName = jarTask,
                docType = null,
                role = PublishAttributes.ROLE_JAR,
                devUsage = PublishAttributes.USAGE_DEV_API,
                // dev artifacts are classified extras only when a prod
                // channel exists to be the main artifact — identity
                // envs and missing remap pipelines leave dev as main
                artifactClassifier = if (prodJar) "dev" else null
            ),
            DevConf(
                confName = runtimeElementsName(sourceSet),
                depBuckets = runtimeDepBuckets(environment, sourceSet),
                artifactTaskName = jarTask,
                docType = null,
                role = PublishAttributes.ROLE_JAR,
                devUsage = PublishAttributes.USAGE_DEV_RUNTIME,
                artifactClassifier = if (prodJar) "dev" else null
            ),
            DevConf(
                confName = sourcesElementsName(sourceSet),
                depBuckets = emptyList(),
                artifactTaskName = sourcesTask,
                registerArtifactIfMissing = true,
                docType = DocsType.SOURCES,
                role = PublishAttributes.ROLE_SOURCES,
                devUsage = PublishAttributes.USAGE_DEV_SOURCES,
                artifactClassifier =
                    if (prodSources) "dev-sources" else "sources"
            )
        )
    }

    /**
     * Return [spec]'s configuration, creating it when missing. Returns
     * null when the conf can't carry an artifact (no producer task).
     */
    fun ensureDevConf(
        project: Project,
        spec: DevConf,
        sourceSet: SourceSet
    ): Configuration? {
        project.configurations.findByName(spec.confName)?.let { existing ->
            spec.artifactTaskName?.let {
                ensureJarArtifact(project, existing.name, it, spec.artifactClassifier)
            }
            return existing
        }
        val task = spec.artifactTaskName?.let { name ->
            project.tasks.findByName(name) as? Jar ?: if (spec.registerArtifactIfMissing) {
                // same fallback ProductionJarWiring applies — a plain
                // sources jar over the source set's sources
                project.tasks.register(name, Jar::class.java) { t ->
                    t.from(sourceSet.allSource)
                    t.archiveClassifier.set("sources")
                    t.destinationDirectory.set(
                        project.layout.buildDirectory.dir("libs")
                    )
                }.get()
            } else null
        }
        if (spec.artifactTaskName != null && task == null) return null

        val conf = project.configurations.maybeCreate(spec.confName).apply {
            isCanBeConsumed = true
            isCanBeResolved = false
        }
        // dev confs carry the dev usage directly — they only ever feed
        // this plugin's component variants, so conf attributes are the
        // variant attributes
        stampConfAttributes(project, conf, spec.devUsage, spec.docType)
        spec.depBuckets.forEach { bucket ->
            project.configurations.findByName(bucket)?.let { conf.extendsFrom(it) }
        }
        task?.let { t ->
            conf.outgoing.artifact(t.archiveFile) { a ->
                a.builtBy(t)
                spec.artifactClassifier?.let { a.classifier = it }
            }
        }
        return conf
    }

    /**
     * Stamp the standard consumable-configuration attribute set. Values
     * are deliberately *not* copied from an existing conf — the java
     * plugin's `org.gradle.jvm.version` is a provider that resolves the
     * whole Minecraft pipeline when read, so copying it would defeat
     * laziness. The jvm-version attribute is stamped lazily by
     * [PublishVariantWiring.stampAttributes] instead.
     */
    fun stampConfAttributes(
        project: Project,
        conf: Configuration,
        usage: String,
        docType: String?
    ) {
        conf.attributes.attribute(
            Usage.USAGE_ATTRIBUTE,
            project.objects.named(Usage::class.java, usage)
        )
        if (docType != null) {
            conf.attributes.attribute(
                Category.CATEGORY_ATTRIBUTE,
                project.objects.named(Category::class.java, Category.DOCUMENTATION)
            )
            conf.attributes.attribute(
                DocsType.DOCS_TYPE_ATTRIBUTE,
                project.objects.named(DocsType::class.java, docType)
            )
        } else {
            conf.attributes.attribute(
                Category.CATEGORY_ATTRIBUTE,
                project.objects.named(Category::class.java, Category.LIBRARY)
            )
            conf.attributes.attribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                project.objects.named(LibraryElements::class.java, LibraryElements.JAR)
            )
        }
        conf.attributes.attribute(
            Bundling.BUNDLING_ATTRIBUTE,
            project.objects.named(Bundling::class.java, Bundling.EXTERNAL)
        )
    }

    private fun ensureJarArtifact(
        project: Project, confName: String, taskName: String,
        classifier: String? = null
    ) {
        val conf = project.configurations.findByName(confName) ?: return
        if (conf.outgoing.artifacts.isNotEmpty()) return
        val task = project.tasks.findByName(taskName) as? Jar ?: return
        conf.outgoing.artifact(task.archiveFile) { a ->
            a.builtBy(task)
            if (classifier != null) a.classifier = classifier
        }
    }

    /** `<ss>Implementation`-style bucket name (`implementation` for
     *  main, `fabricImplementation` for the fabric source set). */
    private fun depBucket(sourceSet: SourceSet, simpleName: String): String =
        if (sourceSet.name == "main") simpleName
        else "${sourceSet.name}${simpleName.replaceFirstChar(Char::uppercase)}"

    /** Dependency parents for the api-channel conf: `api` for main
     *  (java semantics — implementation deps stay off the api view),
     *  `<ss>Api`/`<ss>Implementation` for custom source sets which have
     *  no api/implementation split. */
    private fun apiDepBuckets(
        environment: MinecraftEnvironment,
        sourceSet: SourceSet
    ): List<String> = buildList {
        if (sourceSet.name == "main") {
            add("api")
        } else {
            add("${sourceSet.name}Api")
            add(depBucket(sourceSet, "implementation"))
        }
        add(environment.modImplementationConfigurationName)
    }

    private fun runtimeDepBuckets(
        environment: MinecraftEnvironment,
        sourceSet: SourceSet
    ): List<String> = buildList {
        if (sourceSet.name != "main") {
            // custom source sets have no api/implementation split —
            // api deps (if a `<ss>Api` bucket exists) are runtime-visible
            add("${sourceSet.name}Api")
        }
        add(depBucket(sourceSet, "implementation"))
        add(depBucket(sourceSet, "runtimeOnly"))
        add(environment.modImplementationConfigurationName)
        add(environment.modRuntimeOnlyConfigurationName)
    }

    // configuration names are prefixed with the source set name for
    // *every* environment — including main (`mainApiElements`, not
    // java's shared `apiElements`) so components.java stays untouched

    internal fun apiElementsName(sourceSet: SourceSet): String =
        "${sourceSet.name}ApiElements"

    internal fun runtimeElementsName(sourceSet: SourceSet): String =
        "${sourceSet.name}RuntimeElements"

    internal fun sourcesElementsName(sourceSet: SourceSet): String =
        "${sourceSet.name}SourcesElements"
}
