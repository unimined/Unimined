package xyz.wagyourtail.unimined.provider.devbundle.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import xyz.wagyourtail.unimined.api.util.reportProblem
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.provider.devbundle.DevBundleExport
import xyz.wagyourtail.unimined.provider.devbundle.DevBundleExportSpec

/**
 * `export<Env>DevBundle` — materializes a paperweight dev-bundle zip
 * from the environment's production artifact (see
 * [DevBundleExportSpec] for the configuring surface). The bundle is a
 * self-contained reproducer: the embedded paperclip extracts the prod
 * jar verbatim, so consumers' patched-server output is byte-identical
 * to what this environment built.
 */
@DisableCachingByDefault(
    because = "export packages an environment artifact for external " +
            "consumers — the output is a distribution, not a " +
            "build-cache shape"
)
abstract class ExportDevBundleTask : DefaultTask() {

    /** The production server jar the bundle reproduces. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val serverJar: RegularFileProperty

    @get:Input
    abstract val minecraftVersion: Property<String>

    /** The bundler `version.json`/`versions.list` id. */
    @get:Input
    abstract val bundleId: Property<String>

    /** `group:name:version` coords for `META-INF/libraries.list`. */
    @get:Input
    abstract val libraryCoordinates: ListProperty<String>

    /** Jar files physically embedded into the paperclip. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val embeddedLibraries: ConfigurableFileCollection

    @get:Input
    abstract val libraryRepositories: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val apiCoordinates: Property<String>

    @get:Input
    @get:Optional
    abstract val mojangApiCoordinates: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val reobfMappingsFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val sourcePatches: DirectoryProperty

    @get:Input
    abstract val mainClass: Property<String>

    /** The env's declared side — dev bundles are server artifacts, so
     *  exporting a CLIENT env's jar warns (not fails — a deliberately
     *  overridden [serverJar] may still be a server jar). */
    @get:Input
    @get:Optional
    abstract val envSide: Property<String>

    /** The env's production namespaces — the bundle contract is a
     *  mojmap-named jar, so a non-mojmap prod namespace warns. */
    @get:Input
    abstract val prodNamespaces: ListProperty<String>

    @get:OutputFile
    abstract val outputZip: RegularFileProperty

    @TaskAction
    fun export() {
        if (envSide.orNull == Side.CLIENT.name) {
            project.reportProblem(
                "devbundle/client-side-export",
                "unimined: exporting a CLIENT-side environment — dev " +
                        "bundles carry server artifacts; consumers " +
                        "must still declare SERVER/MERGED sides"
            )
        }
        // warn-only input — an env the provider chain can't fully
        // resolve (no mappings declared etc.) must not fail the export
        val prodNs = runCatching { prodNamespaces.get() }
            .getOrElse { emptyList() }
        if (prodNs.size == 1 && prodNs.single() != "mojmap") {
            project.reportProblem(
                "devbundle/prod-namespace",
                "unimined: the environment's production namespace is " +
                        "'${prodNs.single()}', not 'mojmap' — " +
                        "dev-bundle readers report the artifact as " +
                        "mojmap-named; point serverJar at a " +
                        "mojmap-mapped jar to keep the contract honest"
            )
        }
        val out = DevBundleExport.export(
            DevBundleExport.Spec(
                minecraftVersion = minecraftVersion.get(),
                bundleId = bundleId.get(),
                serverJar = serverJar.get().asFile.toPath(),
                libraries = DevBundleExport.pairLibraries(
                    libraryCoordinates.get(),
                    embeddedLibraries.files.map { it.toPath() }
                ),
                libraryRepositories = libraryRepositories.get(),
                apiCoordinates = apiCoordinates.orNull,
                mojangApiCoordinates = mojangApiCoordinates.orNull,
                reobfMappingsFile =
                    reobfMappingsFile.orNull?.asFile?.toPath(),
                sourcePatches = sourcePatches.orNull?.asFile?.toPath(),
                mainClass = mainClass.get()
            ),
            outputZip.get().asFile.toPath()
        )
        logger.lifecycle("dev bundle exported to $out")
    }

}
