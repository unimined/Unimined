package xyz.wagyourtail.unimined.minecraft.internal

import org.gradle.api.GradleException
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.api.environment.EnvironmentReference
import xyz.wagyourtail.unimined.api.environment.EnvironmentSection
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyRegistry
import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyRequest

/**
 * Env-inheritance sink for [MinecraftEnvironment] — owns the
 * `copyFrom`/`combineWith` bookkeeping (which refs were declared,
 * which sourceSet outputs are already wired) plus the composition
 * wiring that lands a source env's output on this env's classpaths.
 * The public DSL verbs stay on the environment as thin delegates.
 */
internal class EnvironmentInheritance(private val env: MinecraftEnvironment) {

    private val project = env.project

    /** Environments whose source-set output is composed into this one. */
    val references = linkedSetOf<EnvironmentReference>()
    private val wiredOutputReferences = mutableSetOf<EnvironmentReference>()

    /** Sources this env copied VERSION from — checked at bind so
     *  pinned-mismatching envs error instead of silently inheriting
     *  version-sensitive sections. */
    val versionCopySources = linkedSetOf<EnvironmentReference>()

    /** Set by [MinecraftEnvironment.bindSourceSet] — sources declared
     *  after bind wire immediately instead of waiting for the bind-time
     *  sweep. */
    var boundSourceSet: SourceSet? = null

    fun copySections(
        source: EnvironmentReference,
        sections: Set<EnvironmentSection>,
        requireHandlers: Boolean
    ) {
        checkSelfReference(source)
        if (EnvironmentSection.VERSION in sections)
            versionCopySources.add(source)
        EnvironmentCopyRegistry.record(
            project,
            EnvironmentCopyRequest(
                source = source,
                target = EnvironmentReference(project.path, env.environmentId),
                sections = sections
            ),
            requireHandlers
        )
    }

    fun combineWith(
        source: EnvironmentReference,
        sections: Set<EnvironmentSection>,
        requireHandlers: Boolean
    ) {
        checkSelfReference(source)
        copySections(source, sections, requireHandlers)
        if (references.add(source)) {
            boundSourceSet?.let { wireSourceOutput(source, it) }
        }
    }

    private fun checkSelfReference(source: EnvironmentReference) {
        if (source.projectPath == project.path && source.environmentId == env.environmentId) {
            throw GradleException(
                "Minecraft environment '${env.environmentId}' cannot copy or combine with itself"
            )
        }
    }

    fun wireSourceOutput(source: EnvironmentReference, target: SourceSet = boundSourceSet!!) {
        if (!wiredOutputReferences.add(source)) return
        // the source env's COMBINED output, not just its own
        // sourceSet's — composition is transitive (a gametest env
        // combining `fabric` must also see `main`, which `fabric`
        // itself combines). combinedOutputs resolves lazily, so
        // sources wired after this call still land.
        val output = project.files().apply {
            from(project.provider { sourceSet(source).output })
            from(project.provider {
                project.extensions.getByType(MinecraftExtension::class.java)
                    .environment(source).combinedOutputs
            })
        }
        // Gradle 9 marks resolvable compile/runtime classpaths non-declarable.
        // Adding the output to compileOnly+runtimeOnly places it on both
        // classpaths without mutating the resolvable configurations directly.
        // Compile gets classesDirs only — resources on the compile
        // classpath would pull processResources (and anything it
        // consumes, e.g. generateModMetadata → aux-env compile tasks)
        // into the compile graph and can cycle; runtime keeps the full
        // output so resources (fmj etc.) still ship.
        project.dependencies.add(
            target.compileOnlyConfigurationName,
            project.files(project.provider { sourceSet(source).output.classesDirs })
        )
        project.dependencies.add(target.runtimeOnlyConfigurationName, output)
        env.combinedOutputs.from(output)
        env.combinedSources.from(project.provider { sourceSet(source).allSource })
    }

    private fun sourceSet(source: EnvironmentReference): SourceSet {
        val sourceProject = if (source.projectPath == project.path) {
            project
        } else {
            project.project(source.projectPath)
        }
        val sourceSets = sourceProject.extensions.findByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ) ?: throw GradleException(
            "Cannot combine '${project.path}:${env.environmentId}' with " +
                    "'${source.projectPath}:${source.environmentId}': the source " +
                    "project has no Java source sets"
        )
        return sourceSets.getByName(source.environmentId)
    }
}
