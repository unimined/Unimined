package xyz.wagyourtail.unimined.minecraft.internal.env

import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.api.util.failProblem
import xyz.wagyourtail.unimined.api.environment.EnvironmentReference
import xyz.wagyourtail.unimined.api.environment.EnvironmentSection
import xyz.wagyourtail.unimined.minecraft.CombinePackaging
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

    /** Raw DSL packaging-mode strings per combine edge — absent means
     *  the shadow default. Kept as strings so validation stays lazy:
     *  the parse (and its valid-values/not-implemented failures) fires
     *  at the point of use — the post-DSL validation pass and the
     *  packaged-output resolution — never at assignment. */
    val packagingModes = linkedMapOf<EnvironmentReference, String>()

    /** Post-DSL validation pass installs once per env — it reads the
     *  final mode map after every combineWith declaration has run. */
    private var packagingValidationInstalled = false

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
        requireHandlers: Boolean,
        packaging: String? = null
    ) {
        checkSelfReference(source)
        copySections(source, sections, requireHandlers)
        if (packaging != null) {
            // last explicit declaration wins — the wiring reads the
            // map lazily, so even a post-bind redeclare lands; a
            // redeclare carrying no mode leaves the existing one alone
            packagingModes[source] = packaging
            installPackagingValidation()
        }
        if (references.add(source)) {
            boundSourceSet?.let { wireSourceOutput(source, it) }
        }
    }

    /** Validate every declared edge mode once the project's DSL is
     *  final — surfaces invalid strings and not-implemented modes at
     *  configuration time even when the artifact never assembles
     *  (e.g. on the `main` env, whose jar never embeds combined
     *  output). Post-evaluation declarations miss this pass but hit
     *  the same validation lazily when the packaged view resolves. */
    private fun installPackagingValidation() {
        if (packagingValidationInstalled) return
        packagingValidationInstalled = true
        project.afterDslEval {
            packagingModes.forEach { (source, raw) ->
                validatePackagingMode(source, raw)
            }
        }
    }

    /** Parse [raw] into its [CombinePackaging] — the single validation
     *  point shared by the post-DSL pass and the lazy packaged-output
     *  resolution. Unknown values fail listing the valid DSL spellings;
     *  [CombinePackaging.JAR_IN_JAR] parses but hard-errors as
     *  unimplemented — there is no valid use of the mode yet. */
    internal fun validatePackagingMode(
        source: EnvironmentReference,
        raw: String
    ): CombinePackaging {
        val parsed = CombinePackaging.parse(raw) ?: project.failProblem(
            "env/combine-packaging",
            "unimined: environment '${env.environmentId}' — unknown " +
                "combineWith packaging mode \"$raw\" for " +
                "'${source.projectPath}:${source.environmentId}'",
            "expected one of: ${CombinePackaging.DSL_NAMES.joinToString()}"
        )
        if (parsed == CombinePackaging.JAR_IN_JAR) project.failProblem(
            "env/combine-packaging-jarinjar",
            "unimined: environment '${env.environmentId}' — combineWith " +
                "packaging mode \"jarinjar\" for " +
                "'${source.projectPath}:${source.environmentId}' is not " +
                "yet implemented",
            "use \"shadow\" (embed flat in the artifact — the default) " +
                "or \"none\" (compose classpaths only); jar-in-jar " +
                "nesting ships in a future release"
        )
        return parsed
    }

    private fun checkSelfReference(source: EnvironmentReference) {
        if (source.projectPath == project.path && source.environmentId == env.environmentId) {
            project.failProblem(
                "env/self-copy",
                "Minecraft environment '${env.environmentId}' cannot " +
                    "copy or combine with itself"
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
        env.combinedSourceDirs.from(project.provider {
            sourceSet(source).allSource.srcDirs
        })

        // packaged-artifact view — parallel to the classpath
        // composition above but gated on the edge's packaging mode:
        // shadow edges contribute the source's own output plus the
        // source's OWN packaged view recursively (a mode propagates
        // through combine chains — shadowing an env embeds exactly
        // what that env itself ships); none edges compose the
        // classpaths only. The mode string parses inside the provider
        // so validation fires at resolution — after the final mode
        // map settles — not at the combineWith call. When every edge
        // is shadow (the default) this view resolves identically to
        // combinedOutputs.
        env.packagedCombinedOutputs.from(project.provider {
            when (validatePackagingMode(source, packagingModes[source] ?: "shadow")) {
                CombinePackaging.SHADOW -> project.files().apply {
                    from(project.provider { sourceSet(source).output })
                    from(project.provider {
                        project.extensions.getByType(MinecraftExtension::class.java)
                            .environment(source).packagedCombinedOutputs
                    })
                }
                CombinePackaging.NONE -> project.files()
                CombinePackaging.JAR_IN_JAR -> error(
                    "validatePackagingMode hard-errors jarinjar — unreachable")
            }
        })
    }

    private fun sourceSet(source: EnvironmentReference): SourceSet {
        val sourceProject = if (source.projectPath == project.path) {
            project
        } else {
            project.project(source.projectPath)
        }
        val sourceSets = sourceProject.extensions.findByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ) ?: project.failProblem(
            "env/source-sets-missing",
            "Cannot combine '${project.path}:${env.environmentId}' with " +
                    "'${source.projectPath}:${source.environmentId}': the source " +
                    "project has no Java source sets"
        )
        return sourceSets.getByName(source.environmentId)
    }
}
