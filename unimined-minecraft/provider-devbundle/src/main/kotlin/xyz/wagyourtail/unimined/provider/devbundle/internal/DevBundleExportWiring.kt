package xyz.wagyourtail.unimined.provider.devbundle.internal

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.api.util.registerDeprecatedTaskAlias
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.provider.devbundle.DevBundleExport
import xyz.wagyourtail.unimined.provider.devbundle.DevBundleExportSpec
import xyz.wagyourtail.unimined.provider.devbundle.task.ExportDevBundleTask

/**
 * Per-environment export wiring — contributes the `devBundleExport`
 * extension and registers `export<Env>DevBundle`. Every convention is
 * lazy: the env's DSL may still be settling when [wire] runs (env
 * objects appear while `environments.all` fires), and the prod view /
 * library configurations resolve at task execution, never here.
 */
internal object DevBundleExportWiring {

    fun wire(project: Project, environment: MinecraftEnvironment) {
        val environmentId = environment.environmentId
        val minecraft =
            project.extensions.getByType(MinecraftExtension::class.java)
        val spec = environment.extensions.create(
            "devBundleExport", DevBundleExportSpec::class.java
        )
        val capId = environmentId.replaceFirstChar { it.uppercase() }

        spec.minecraftVersion.convention(environment.version)
        spec.bundleId.convention(
            environment.version.map { "unimined-$environmentId-$it" }
        )
        // the env's prod jar — `minecraftProd` resolves the whole JAR
        // pipeline in the production namespace (transform plugin's
        // raw→prod transform); the `matching` view defers the lookup so
        // wiring order can't matter, and a missing view surfaces as a
        // named error only when the task actually runs
        spec.serverJar.convention(project.layout.file(
            project.configurations
                .matching { it.name == environment.minecraftProdViewName }
                .elements.flatMap { confs ->
                    confs.singleOrNull()?.elements
                        ?: project.providers.provider {
                            throw GradleException(
                                "devbundle export needs the " +
                                        "'${environment.minecraftProdViewName}' " +
                                        "view — apply " +
                                        "xyz.wagyourtail.unimined.transform " +
                                        "or set devBundleExport.serverJar " +
                                        "explicitly"
                            )
                        }
                }.map { it.single().asFile }
        ))
        // the env's declared library set → libraries.list coords;
        // matching views stay empty (not noisy) when the env was never
        // bound to a source set
        spec.libraryCoordinates.convention(
            project.configurations
                .matching {
                    it.name == environment.minecraftLibrariesConfigurationName
                }.elements.map { confs ->
                    confs.singleOrNull()?.allDependencies?.map {
                        "${it.group}:${it.name}:${it.version}"
                    } ?: emptyList()
                }
        )
        // resolved library artifacts → embedded jars (a full,
        // launchable bundle); `setFrom()` empties for slim exports.
        // `.files` resolves at task fingerprint — the conf is the env's
        // library bucket, already needed for any dev work
        spec.embeddedLibraries.from(
            project.configurations
                .matching {
                    it.name == environment.minecraftLibrariesConfigurationName
                }.elements.map { confs ->
                    confs.singleOrNull()?.files?.toList() ?: emptyList()
                }
        )
        // convention-able now so the task runs pre-evaluation; the
        // post-DSL block replaces it — repo declarations may land
        // after this wiring
        spec.libraryRepositories.convention(emptyList())
        project.afterDslEval {
            spec.libraryRepositories.convention(
                it.repositories
                    .filterIsInstance<MavenArtifactRepository>()
                    .map { r -> r.url.toString() }
                    .filter { url -> !url.startsWith("file:") }
            )
        }
        spec.mainClass.convention("net.minecraft.server.Main")
        spec.outputZip.convention(
            project.layout.buildDirectory
                .file("devBundle/$environmentId-dev-bundle.zip")
        )

        // verb<Env>Noun naming: `exportDevBundle` for `main` (elided),
        // `export<Fabric>DevBundle` otherwise; `exportMainDevBundle`
        // stays as a deprecated alias
        val exportTask = project.tasks.register(
            if (environmentId == "main") "exportDevBundle"
            else "export${capId}DevBundle", ExportDevBundleTask::class.java
        ) { t ->
            t.group = "unimined"
            t.description = "Exports the '$environmentId' environment's " +
                    "production artifact as a paperweight dev-bundle zip"
            t.envSide.set(environment.side.map { it.name })
            t.prodNamespaces.set(minecraft.modSourceNamespaces(environmentId))
            t.serverJar.set(spec.serverJar)
            t.minecraftVersion.set(spec.minecraftVersion)
            t.bundleId.set(spec.bundleId)
            t.libraryCoordinates.set(spec.libraryCoordinates)
            t.embeddedLibraries.from(spec.embeddedLibraries)
            t.libraryRepositories.set(spec.libraryRepositories)
            t.apiCoordinates.set(spec.apiCoordinates)
            t.mojangApiCoordinates.set(spec.mojangApiCoordinates)
            t.reobfMappingsFile.set(spec.reobfMappingsFile)
            t.sourcePatches.set(spec.sourcePatches)
            t.mainClass.set(spec.mainClass)
            t.outputZip.set(spec.outputZip)
        }
        if (environmentId == "main") {
            project.registerDeprecatedTaskAlias(
                "exportMainDevBundle", exportTask)
        }
    }

}
