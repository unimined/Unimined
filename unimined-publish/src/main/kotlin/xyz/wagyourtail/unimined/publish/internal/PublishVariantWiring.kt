package xyz.wagyourtail.unimined.publish.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.publish.PublishAttributes
import xyz.wagyourtail.unimined.publish.PublishExtension

/**
 * Per-environment publish variants (plan §11).
 *
 * Everything runs at `projectsEvaluated`: the java plugin only creates
 * `apiElements`/`runtimeElements`/`sourcesElements`/`javadocElements`
 * for `main`, and those confs are shared with `components.java` — so
 * this wiring creates *its own* consumable configurations per
 * environment (`mainApiElements`, `fabricApiElements`, ...) and stamps
 * the full attribute set on them eagerly. Conf attributes ARE the
 * published variant attributes (configuration ↔ variant is 1:1), and
 * stamping must happen before Gradle locks consumable confs — the
 * `addVariantsFromConfiguration` details action runs lazily at
 * `computeVariants`, which is too late (`Cannot change attributes of
 * configuration ... after it has been locked for mutation`). Keeping
 * unimined confs separate from java's also means `components.java`
 * (the `javaPublication()` opt-in) is never polluted with dev usages
 * or `unimined.*` attributes.
 *
 * - **dev channel** — the source set's own jar tasks (dev namespace,
 *   `-dev` classifier). Conf/variant `Usage` is a custom
 *   `unimined-dev-*` value so plain `java-api`/`java-runtime` consumers
 *   never match them; only consumers that explicitly request a dev
 *   usage (or the `unimined.*` attributes) see them. This keeps
 *   `implementation "com.example:mymod-fabric:1.0"` resolving the
 *   production variant unambiguously.
 * - **prod channel** — `remap<Jar>`/`remap<Sources>Jar` outputs remapped
 *   to the production namespace, carried on `*Prod*Elements`
 *   consumable configurations mirroring the dev dependency view. The
 *   artifact provider falls back to the dev output when the remap task
 *   self-skips (identity environments), so identity envs publish the
 *   same file under both channels without resolving namespaces at
 *   configuration time. Identity envs that declare equal dev/prod
 *   namespaces skip prod variants entirely (one artifact, one
 *   coordinate — plan §11).
 * - **data files** — `<ss>DataElements` when such a configuration
 *   exists (no producer ships yet; the toggle is forward-looking).
 *
 * `publishDev`/`publishProduction`/`publishDataFiles` gate each group.
 */
internal object PublishVariantWiring {

    fun wire(
        project: Project,
        environment: MinecraftEnvironment,
        sourceSet: SourceSet,
        envPub: PublishExtension.EnvironmentPublication,
        component: AdhocComponentWithVariants
    ) {
        // task registration (env jars, remap tasks, sources/javadoc
        // jars) completes in other plugins' afterEvaluate — all variant
        // wiring is deferred to projectsEvaluated
        project.gradle.projectsEvaluated {
            wireVariants(project, environment, sourceSet, envPub, component)
        }
    }

    /** The `projectsEvaluated` body — internal so tests can drive it
     *  without a full build lifecycle. */
    internal fun wireVariants(
        project: Project,
        environment: MinecraftEnvironment,
        sourceSet: SourceSet,
        envPub: PublishExtension.EnvironmentPublication,
        component: AdhocComponentWithVariants
    ) {
        val devNamespace = environment.devNamespace.orNull
        val prodNamespace = environment.modSourceNamespaces.orNull?.singleOrNull()
        // declared-namespace identity check only — resolving
        // isIdentityRemap would run the whole Minecraft pipeline at
        // configuration time; the selector tasks cover the unresolved
        // case at execution
        val declaredIdentity = devNamespace != null &&
                environment.modSourceNamespaces.orNull?.singleOrNull() == devNamespace

        // which prod artifacts will exist — decides whether dev
        // artifacts publish as classified extras or as the module's
        // main (null-classifier) files: Maven publications need a
        // unique (extension, classifier) per artifact and reject a
        // publication with no main artifact
        val remapJarName = "remap${sourceSet.jarTaskName.replaceFirstChar(Char::uppercase)}"
        val remapSourcesName =
            "remap${sourceSet.sourcesJarTaskName.replaceFirstChar(Char::uppercase)}"
        val prodJar = envPub.publishProduction && !declaredIdentity &&
                project.tasks.findByName(remapJarName) is Jar
        val prodSources = envPub.publishProduction && !declaredIdentity &&
                project.tasks.findByName(remapSourcesName) is Jar

        // dev confs are created unconditionally — they double as the
        // prod templates and project-dependency targets; only the
        // component variants are gated by publishDev
        val devConfs = PublishDevVariantWiring.ensureDevConfigurations(
            project, environment, sourceSet, prodJar, prodSources)

        if (envPub.publishDev) {
            for ((confName, spec) in devConfs) {
                addVariant(project, confName, component) { conf ->
                    stampAttributes(
                        conf.attributes, project, environment,
                        PublishAttributes.CHANNEL_DEV, spec.role,
                        devNamespace
                    )
                }
            }
            // javadoc is only published when a javadocJar producer
            // exists (java.withJavadocJar or a custom registration) —
            // creating it unconditionally would run javadoc on every
            // publish and fail on doclint errors
            val javadocJarName = javadocJarTaskName(sourceSet)
            if (project.tasks.findByName(javadocJarName) is Jar) {
                val spec = PublishDevVariantWiring.DevConf(
                    confName = javadocElementsName(sourceSet),
                    depBuckets = emptyList(),
                    artifactTaskName = javadocJarName,
                    docType = DocsType.JAVADOC,
                    role = PublishAttributes.ROLE_JAVADOC,
                    devUsage = PublishAttributes.USAGE_DEV_JAVADOC,
                    artifactClassifier = "javadoc"
                )
                PublishDevVariantWiring.ensureDevConf(project, spec, sourceSet)?.let { conf ->
                    stampAttributes(
                        conf.attributes, project, environment,
                        PublishAttributes.CHANNEL_DEV, spec.role,
                        devNamespace
                    )
                    component.addVariantsFromConfiguration(conf) {}
                }
            }
        }

        if (envPub.publishDataFiles) {
            val dataConf = dataElementsName(sourceSet)
            project.configurations.findByName(dataConf)?.let { conf ->
                stampAttributes(
                    conf.attributes, project, environment,
                    null, PublishAttributes.ROLE_DATA, null,
                    PublishAttributes.USAGE_DATA
                )
                component.addVariantsFromConfiguration(conf) {}
            }
        }

        if (envPub.publishProduction) {
            wireProdVariants(
                project, environment, sourceSet, component, prodNamespace,
                declaredIdentity
            )
        }
    }

    // ------------------------------------------------------------------
    // dev channel — ensure the per-source-set consumable configurations
    // ------------------------------------------------------------------

    /** One dev-channel outgoing configuration plus how to stamp it. */
    internal fun stampConfAttributes(
        project: Project,
        conf: Configuration,
        usage: String,
        docType: String?
    ) = PublishDevVariantWiring.stampConfAttributes(project, conf, usage, docType)

    // ------------------------------------------------------------------
    // prod channel
    // ------------------------------------------------------------------

    /**
     * Production variants: `remap<Jar>`/`remap<Sources>Jar` outputs on
     * `*Prod*Elements` consumable configurations that mirror the dev
     * dependency view. Environments whose dev/prod namespaces are
     * declared equal skip prod entirely (identity — one artifact).
     */
    private fun wireProdVariants(
        project: Project,
        environment: MinecraftEnvironment,
        sourceSet: SourceSet,
        component: AdhocComponentWithVariants,
        prodNamespace: String?,
        declaredIdentity: Boolean
    ) {
        if (declaredIdentity) return

        val jarTaskName = sourceSet.jarTaskName
        val sourcesTaskName = sourceSet.sourcesJarTaskName
        val remapJarName = "remap${jarTaskName.replaceFirstChar(Char::uppercase)}"
        val remapSourcesName =
            "remap${sourcesTaskName.replaceFirstChar(Char::uppercase)}"
        val specs = listOf(
            ProdSpec(
                prodApiElementsName(sourceSet),
                PublishDevVariantWiring.apiElementsName(sourceSet),
                jarTaskName, remapJarName,
                PublishAttributes.ROLE_JAR, Usage.JAVA_API, null,
                artifactClassifier = null
            ),
            ProdSpec(
                prodRuntimeElementsName(sourceSet),
                PublishDevVariantWiring.runtimeElementsName(sourceSet),
                jarTaskName, remapJarName,
                PublishAttributes.ROLE_JAR, Usage.JAVA_RUNTIME, null,
                artifactClassifier = null
            ),
            ProdSpec(
                prodSourcesElementsName(sourceSet),
                PublishDevVariantWiring.sourcesElementsName(sourceSet),
                sourcesTaskName, remapSourcesName,
                PublishAttributes.ROLE_SOURCES, Usage.JAVA_RUNTIME, DocsType.SOURCES,
                artifactClassifier = "sources"
            )
        )

        // one selector task per remap source — the api/runtime prod
        // confs share the jar selector
        val selectors = mutableMapOf<String, Pair<TaskProvider<Sync>, Provider<RegularFile>>>()

        for (spec in specs) {
            val remapTask = project.tasks.findByName(spec.remapTaskName) as? Jar
                ?: continue // no remap pipeline — nothing to publish as prod
            // the dev conf supplies the dependency parents; it exists
            // whenever the dev task does (ensureDevConfigurations)
            val devConf = project.configurations.findByName(spec.devConfName)
                ?: continue
            val devTask = project.tasks.findByName(spec.devTaskName) as? Jar
            val (selector, output) = selectors.getOrPut(spec.remapTaskName) {
                prodArtifactTask(project, environment, remapTask, devTask,
                    spec.remapTaskName)
            }
            val conf = prodConfiguration(project, spec.confName, devConf, spec)
            conf.outgoing.artifact(output) { a ->
                a.builtBy(selector)
                // prod jar stays the null-classifier main artifact;
                // sources get the conventional 'sources' classifier —
                // Maven publications reject duplicate (ext, classifier)
                spec.artifactClassifier?.let { a.classifier = it }
            }
            stampAttributes(
                conf.attributes, project, environment,
                PublishAttributes.CHANNEL_PROD, spec.role, prodNamespace
            )
            component.addVariantsFromConfiguration(conf) {}
        }
    }

    private class ProdSpec(
        val confName: String,
        /** dev-channel conf to mirror dependency parents from. */
        val devConfName: String,
        val devTaskName: String,
        val remapTaskName: String,
        val role: String,
        val confUsage: String,
        val docType: String?,
        /** Maven classifier — null keeps the artifact as the
         *  publication's main (null-classifier) file. */
        val artifactClassifier: String?
    )

    /**
     * A `Sync` task that selects the prod artifact at *execution*:
     * the remap task's output when it ran, else the dev task's output
     * — the remap task self-skips for identity environments
     * (`onlyIf { !isIdentityRemap }`, resolved lazily), where dev and
     * prod are the same namespace and the dev jar *is* the production
     * artifact.
     *
     * The choice cannot live in an artifact [Provider]: Gradle
     * realizes artifact providers while building the task graph —
     * before `remap<Jar>` has run — so a file-existence probe there
     * always sees "missing" (and task-output-mapped providers can't
     * even be queried that early). A task's `from` resolves after its
     * dependencies complete, which is exactly the right moment. The
     * output name is pinned so the artifact provider is a plain path.
     */
    private fun prodArtifactTask(
        project: Project,
        environment: MinecraftEnvironment,
        remapTask: Jar,
        devTask: Jar?,
        remapTaskName: String
    ): Pair<TaskProvider<Sync>, Provider<RegularFile>> {
        val envId = environment.environmentId
        val kind = remapTaskName.removePrefix("remap")
        val dir = "unimined-publish/$envId/${kind.replaceFirstChar(Char::lowercase)}"
        // capture the tasks' archiveFile *providers*, not the task
        // instances — task objects inside a serialized provider are
        // not configuration-cache friendly
        val remapOut = remapTask.archiveFile
        val devOut = devTask?.archiveFile
        val selector = project.tasks.register(
            "uniminedProd$kind", Sync::class.java
        ) { t ->
            t.group = "unimined"
            t.description = "Selects the production '$kind' artifact " +
                    "for env '$envId' (remapped output, or the dev " +
                    "output when the env does not remap)"
            t.dependsOn(remapTask)
            if (devTask != null) t.dependsOn(devTask)
            t.from(project.provider {
                val remapFile = remapOut.get().asFile
                if (remapFile.isFile) {
                    remapFile
                } else {
                    devOut?.get()?.asFile
                        ?: throw org.gradle.api.GradleException(
                            "unimined-publish: environment '$envId' has " +
                                    "publishProduction=true but no dev " +
                                    "jar task to fall back to when " +
                                    "'$remapTaskName' is skipped"
                        )
                }
            })
            t.into(project.layout.buildDirectory.dir(dir))
            t.eachFile { it.name = "artifact.jar" }
        }
        val output = project.layout.buildDirectory.file("$dir/artifact.jar")
        return selector to output
    }

    /**
     * A consumable configuration mirroring [devConf]'s dependency
     * parents — the prod variant shares the dev variant's dependency
     * view but not its jar. Attributes are stamped fresh (not copied)
     * for the same laziness reason as [stampConfAttributes].
     */
    private fun prodConfiguration(
        project: Project,
        name: String,
        devConf: Configuration,
        spec: ProdSpec
    ): Configuration {
        val conf = project.configurations.maybeCreate(name).apply {
            isCanBeConsumed = true
            isCanBeResolved = false
        }
        devConf.extendsFrom.forEach { conf.extendsFrom(it) }
        stampConfAttributes(project, conf, spec.confUsage, spec.docType)
        return conf
    }

    // ------------------------------------------------------------------
    // shared helpers
    // ------------------------------------------------------------------

    /** Stamp [conf]'s attributes then expose it as a component variant.
     *  Stamping is eager: `configurationVariant.attributes` inside the
     *  `addVariantsFromConfiguration` details action is the conf's live
     *  attribute container, already locked by the time the action runs
     *  lazily at variant realization. */
    private fun addVariant(
        project: Project,
        confName: String,
        component: AdhocComponentWithVariants,
        stamp: (Configuration) -> Unit
    ) {
        project.configurations.findByName(confName)?.let { conf ->
            stamp(conf)
            component.addVariantsFromConfiguration(conf) {}
        }
    }

    internal fun stampAttributes(
        attributes: AttributeContainer,
        project: Project,
        environment: MinecraftEnvironment,
        channel: String?,
        role: String,
        namespace: String?,
        usageOverride: String? = null
    ) {
        attributes.attribute(PublishAttributes.ENVIRONMENT, environment.environmentId)
        environment.loaderSpec.orNull?.name?.let {
            attributes.attribute(PublishAttributes.LOADER, it)
        }
        namespace?.let { attributes.attribute(PublishAttributes.NAMESPACE, it) }
        channel?.let { attributes.attribute(PublishAttributes.CHANNEL, it) }
        attributes.attribute(PublishAttributes.ARTIFACT_ROLE, role)
        usageOverride?.let {
            attributes.attribute(
                Usage.USAGE_ATTRIBUTE,
                project.objects.named(Usage::class.java, it)
            )
        }
        // the env's bytecode target — lazy: resolves the Minecraft
        // pipeline at variant realization/consumption, never during
        // configuration (and is more correct than the java extension's
        // targetCompatibility, which unimined doesn't drive)
        attributes.attributeProvider(
            TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,
            environment.requiredJavaVersion
        )
    }

    /** Wire a jar task's output onto a conf that lacks artifacts. */
    private fun javadocElementsName(sourceSet: SourceSet): String =
        "${sourceSet.name}JavadocElements"

    private fun javadocJarTaskName(sourceSet: SourceSet): String =
        if (sourceSet.name == "main") "javadocJar"
        else "${sourceSet.name}JavadocJar"

    private fun dataElementsName(sourceSet: SourceSet): String =
        "${sourceSet.name}DataElements"

    private fun prodApiElementsName(sourceSet: SourceSet): String =
        "${sourceSet.name}ProdApiElements"

    private fun prodRuntimeElementsName(sourceSet: SourceSet): String =
        "${sourceSet.name}ProdRuntimeElements"

    private fun prodSourcesElementsName(sourceSet: SourceSet): String =
        "${sourceSet.name}ProdSourcesElements"

}
