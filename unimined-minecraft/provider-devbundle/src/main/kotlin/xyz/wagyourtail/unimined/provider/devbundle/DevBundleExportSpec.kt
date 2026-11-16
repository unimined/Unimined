package xyz.wagyourtail.unimined.provider.devbundle

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
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
import xyz.wagyourtail.unimined.api.util.delegatingTo
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment

/**
 * Per-environment dev-bundle export settings, contributed by the
 * `xyz.wagyourtail.unimined.provider.devbundle` plugin as the
 * `devBundleExport` extension on every [MinecraftEnvironment] —
 * `sourceSet("main") { devBundleExport { serverJar.set(...) } }`.
 *
 * Pure configuration — nothing runs unless the `export<Env>DevBundle`
 * task is invoked (the task is the opt-in; there is no `enabled`
 * flag). Defaults make a plain `devBundleExport {}` usable: the env's
 * production artifact, version, and library/repo lists all convention
 * onto the environment.
 *
 * The produced zip is a paperweight dev bundle (data version 6):
 * `data/paperclip-mojang.jar` carries the prod jar embedded inside a
 * self-contained reproducer — consumers running it get the exact
 * artifact back (no binary patching involved), and unimined's
 * `devbundle { file(...) }` provider path reads it directly.
 */
abstract class DevBundleExportSpec {

    /** The production server jar the bundle reproduces — defaults to
     *  the environment's `minecraftProd` view (the prod-namespace
     *  artifact through the whole JAR pipeline). Must be a server
     *  artifact, mojmap-named to keep the bundle's contract honest. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val serverJar: RegularFileProperty

    /** The bundle's `minecraftVersion` — conventions to the env's
     *  `version`. */
    @get:Input
    abstract val minecraftVersion: Property<String>

    /** The bundler `version.json`/`versions.list` id — the embedded
     *  jar's file stem too. Conventions to `unimined-<env>-<version>`. */
    @get:Input
    abstract val bundleId: Property<String>

    /** `group:name:version` coords written to the paperclip's
     *  `META-INF/libraries.list` — the consumer's library channel.
     *  Conventions to the env's declared `minecraft<Env>Libraries`
     *  dependencies; add fork-published artifacts on top. */
    @get:Input
    abstract val libraryCoordinates: ListProperty<String>

    /** Jar files physically embedded into the paperclip
     *  (`META-INF/libraries/<name>`) — conventions to the resolved
     *  `minecraft<Env>Libraries` artifacts (a full, launchable bundle).
     *  `setFrom()` empty for a coords-only slim bundle. Files pair to
     *  [libraryCoordinates] rows by the maven filename convention. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val embeddedLibraries: ConfigurableFileCollection

    /** Maven repo URLs mirrored into `config.json` so consumers can
     *  resolve [libraryCoordinates] — conventions to the project's
     *  declared (non-`file:`) maven repositories, read post-DSL. */
    @get:Input
    abstract val libraryRepositories: ListProperty<String>

    /** Optional fork api coordinate for the consumer's compile
     *  classpath (`apiCoordinates` — a `buildData`-era field the
     *  reader still honors at any data version). */
    @get:Input
    @get:Optional
    abstract val apiCoordinates: Property<String>

    /** Optional mojang-mapped api coordinate (`mojangApiCoordinates`). */
    @get:Input
    @get:Optional
    abstract val mojangApiCoordinates: Property<String>

    /** Optional mojmap→prod reobf mappings shipped at
     *  `data/mojang-spigot-reobf.tiny` and declared in `config.json`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val reobfMappingsFile: RegularFileProperty

    /** Optional source-patch tree copied verbatim into `patches/` —
     *  e.g. a patchbase-produced `*.patch` set. Informational for
     *  unimined consumers (the embedded jar is already patched), real
     *  paperweight consumers apply them for source-level work. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val sourcePatches: DirectoryProperty

    /** The server main class written to `META-INF/main-class` —
     *  used when the bundle is launched like a real paperclip. */
    @get:Input
    abstract val mainClass: Property<String>

    /** The bundle zip — defaults to
     *  `build/devBundle/<env>-dev-bundle.zip`. */
    @get:OutputFile
    abstract val outputZip: RegularFileProperty

}

/**
 * DSL accessor for the env's `devBundleExport` extension:
 * `sourceSet("main") { devBundleExport { outputZip.set(...) } }`.
 * Pure configuration — `export<Env>DevBundle` performs the write.
 */
fun MinecraftEnvironment.devBundleExport(
    action: Action<DevBundleExportSpec>
): DevBundleExportSpec =
    extensions.getByType(DevBundleExportSpec::class.java)
        .also { action.execute(it) }

/** Groovy DSL form of [devBundleExport]. */
fun MinecraftEnvironment.devBundleExport(
    @DelegatesTo(
        value = DevBundleExportSpec::class,
        strategy = Closure.DELEGATE_FIRST
    ) action: Closure<*>
): DevBundleExportSpec =
    devBundleExport(Action { action.delegatingTo(it) })
