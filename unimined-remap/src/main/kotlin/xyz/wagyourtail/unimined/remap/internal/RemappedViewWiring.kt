package xyz.wagyourtail.unimined.remap.internal

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.RemappedViewSet
import xyz.wagyourtail.unimined.remap.RemappedViewSpec
import xyz.wagyourtail.unimined.remap.RemappedViewsSpec
import xyz.wagyourtail.unimined.remap.transform.RemapDependencyTransform

/**
 * Remapped-dependency-view wiring — the artifact-transform
 * registration that makes a `modImplementation`-style configuration
 * resolve through `RemapDependencyTransform`. Owns view creation,
 * per-namespace view-set construction, artifact-type naming, and the
 * same-spec transform dedup. The public verbs on [RemapExtension]
 * populate a spec and delegate here.
 */
internal class RemappedViewWiring(private val ext: RemapExtension) {

    private val project = ext.project

    /** artifactType → first spec registered for it — a second view
     *  onto the same type must carry an identical spec (the transform
     *  params are type-global); the discriminator exists to split
     *  genuinely-different specs into distinct types. */
    private val transformRegistrations = mutableMapOf<String, RemappedViewSpec>()

    fun artifactToken(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "-")

    fun defaultViewName(environmentId: String, source: String): String =
        if (environmentId == "main" || source.startsWith(environmentId, true)) {
            "${source}Remapped"
        } else {
            "${environmentId}${source.replaceFirstChar(Char::uppercase)}Remapped"
        }

    fun createRemappedView(
        environmentId: String,
        source: String,
        name: String,
        spec: RemappedViewSpec
    ): Configuration {
        val artifactType = spec.artifactTypeToken.orNull ?: spec.targetNamespace.get().let {
            "unimined-remapped-$environmentId-$it" + spec.discriminator.get()
        }
        val view = project.configurations.create(name) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.extendsFrom(project.configurations.getByName(source))
            conf.attributes.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, artifactType
            )
        }
        registerRemapTransform(spec, artifactType, "$environmentId/$source")
        return view
    }

    /**
     * Build the per-namespace view set for a multi-namespace
     * [spec] — one source bucket + one remapped view per declared
     * source namespace, packaged as a [RemappedViewSet].
     */
    fun buildViewSet(
        environmentId: String,
        baseName: String,
        spec: RemappedViewsSpec
    ): RemappedViewSet {
        val views = linkedMapOf<String, Configuration>()
        for (namespace in spec.namespaces) {
            val sourceConfiguration = project.configurations.maybeCreate(
                namespace.sourceConfiguration
            ).apply {
                isCanBeConsumed = false
                isCanBeResolved = false
            }
            val viewSpec = ext.objects.newInstance(RemappedViewSpec::class.java)
            viewSpec.sourceNamespace.set(namespace.sourceNamespace)
            viewSpec.targetNamespace.set(spec.targetNamespace)
            viewSpec.discriminator.set(
                spec.discriminator.get() + "-from-" + artifactToken(namespace.sourceNamespace)
            )
            if (namespace.mappingFile.isPresent) {
                viewSpec.mappingFile.set(namespace.mappingFile)
            }
            viewSpec.classpath.from(spec.classpath)
            viewSpec.accessModel.set(spec.accessModel)
            viewSpec.stepSpecs.set(spec.stepSpecs)
            viewSpec.modMarkers.set(spec.modMarkers)
            viewSpec.onlyRemapMarkedJars.set(spec.onlyRemapMarkedJars)

            views[namespace.sourceNamespace] = createRemappedView(
                environmentId,
                sourceConfiguration.name,
                defaultViewName(environmentId, sourceConfiguration.name),
                viewSpec
            )
        }
        return RemappedViewSet(
            baseName,
            views.toMap(),
            project.files(views.values)
        )
    }

    private fun registerRemapTransform(
        spec: RemappedViewSpec,
        artifactType: String,
        label: String
    ) {
        val existing = transformRegistrations[artifactType]
        if (existing != null) {
            require(
                existing.sourceNamespace.get() == spec.sourceNamespace.get() &&
                        existing.targetNamespace.get() == spec.targetNamespace.get() &&
                        existing.mappingFile.orNull?.asFile == spec.mappingFile.orNull?.asFile &&
                        existing.hasLazyMappings == spec.hasLazyMappings &&
                        existing.accessModel.isPresent == spec.accessModel.isPresent &&
                        existing.classpath.files == spec.classpath.files &&
                        existing.stepSpecs.getOrElse(emptyList()) == spec.stepSpecs.getOrElse(emptyList())
            ) {
                "remap target '${spec.targetNamespace.get()}' already registered with a different spec — " +
                        "set a discriminator on one of the views"
            }
            return
        }
        transformRegistrations[artifactType] = spec

        project.dependencies.registerTransform(RemapDependencyTransform::class.java) { t ->
            t.from.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                ArtifactTypeDefinition.JAR_TYPE
            )
            t.to.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                artifactType
            )
            t.parameters { p ->
                p.label.set(label)
                p.modMarkers.set(project.provider {
                    if (spec.onlyRemapMarkedJars.get()) spec.modMarkers.get()
                    else emptyList()
                })
                p.sourceNamespace.set(spec.sourceNamespace)
                p.targetNamespace.set(spec.targetNamespace)
                if (spec.hasLazyMappings) {
                    p.mappingBundleJson.set(spec.mappingBundleJson)
                } else if (spec.mappingFile.isPresent) {
                    p.mappingFile.set(spec.mappingFile)
                }
                if (spec.accessModel.isPresent) {
                    p.accessModel.set(spec.accessModel)
                }
                p.classpath.from(spec.classpath)
                p.stepSpecs.set(spec.stepSpecs)
            }
        }
    }
}
