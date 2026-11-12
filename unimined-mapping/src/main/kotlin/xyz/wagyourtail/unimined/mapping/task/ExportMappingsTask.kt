package xyz.wagyourtail.unimined.mapping.task

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import xyz.wagyourtail.unimined.api.mapping.SerializedMappingData
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.util.delegatingTo
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.element.MetadataElement
import xyz.wagyourtail.unimined.mapping.element.metadata.JavadocElement
import xyz.wagyourtail.unimined.mapping.formats.FormatRegistry
import xyz.wagyourtail.unimined.mapping.formats.FormatWriterSettings
import xyz.wagyourtail.unimined.mapping.sink.MappingSink
import xyz.wagyourtail.unimined.mapping.sink.emit
import javax.inject.Inject

/**
 * Exports an environment's resolved mappings to arbitrary UML formats
 * (1.x `exportMappings<Env>` parity):
 *
 * ```kotlin
 * exportMappings {
 *     export {
 *         type.set("tsrg")
 *         location.set(layout.buildDirectory.file("mappings.tsrg"))
 *         sourceNamespace.set("intermediary")
 *         targetNamespaces.set(listOf("named"))
 *     }
 * }
 * ```
 *
 * The resolved tree arrives as the [inputBundleFile] serialized bundle —
 * the same configuration-cache-safe channel `MigrateMappingsTask` and
 * remap tasks consume; no resolution happens at task-configuration
 * time.
 */
@DisableCachingByDefault(
    because = "export specs declare user paths and per-spec outputs; " +
            "the task is cheap and conventionally idempotent")
abstract class ExportMappingsTask @Inject constructor(
    objects: ObjectFactory
) : DefaultTask() {

    /** The environment this export serves — drives the provider context. */
    @get:Input
    abstract val environmentId: Property<String>

    /** The environment's serialized resolved mappings (full tree). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputBundleFile: RegularFileProperty

    /** Declared exports — each is validated at declaration time. */
    @get:Nested
    abstract val exports: ListProperty<ExportSpec>

    private val objects = objects

    /** Declare one export — see the class docs for the DSL shape. */
    fun export(action: Action<ExportSpec>) {
        val spec = objects.newInstance(ExportSpec::class.java)
        action.execute(spec)
        exports.add(spec)
    }

    /** Groovy DSL twin of [export]. */
    fun export(
        @DelegatesTo(value = ExportSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = export(Action { action.delegatingTo(it) })

    @TaskAction
    fun exportMappings() {
        val slog = uniminedLogger(environmentId.get(), logger)
            .subLogger("mappings")
        val data = SerializedMappingData.readEither(
            inputBundleFile.get().asFile.toPath()
        )
        val tree = data.view<MappingTree>()
            ?: throw GradleException(
                "exportMappings($name): the input mapping bundle for " +
                "environment '${environmentId.get()}' carries no mapping tree"
            )
        for (spec in exports.get()) {
            spec.validate()
            val type = spec.type.get()
            val writer = FormatRegistry.formats.firstOrNull {
                it.name.equals(type, ignoreCase = true)
            } ?: throw GradleException(
                "exportMappings($name): unknown mapping format '$type' — " +
                "known: ${FormatRegistry.formats.joinToString { it.name }}"
            )
            val envType = EnvType.valueOf(spec.envType.get().uppercase())
            val settings = FormatWriterSettings(envType)
            val src = Namespace(spec.sourceNamespace.get())
            val dst = spec.targetNamespaces.get().map { Namespace(it) }
            val renames = spec.renameNs.get()
                .mapKeys { (k, _) -> Namespace(k) }
                .mapValues { (_, v) -> Namespace(v) }
            val file = spec.location.get().asFile
            file.parentFile?.mkdirs()
            slog.lifecycle(
                "exporting ${src.name} -> [${dst.joinToString { it.name }}] " +
                "as ${writer.name} to ${file.name}"
            )
            file.bufferedWriter().use { w ->
                val base = writer.write(w::append, settings)
                val sink: MappingSink =
                    if (spec.skipComments.get()) {
                        object : MappingSink by base {
                            // 1.x Delegator javadoc drop — filter meta
                            // before the writer sees it
                            override fun push(meta: MetadataElement) =
                                if (meta is JavadocElement) null
                                else base.push(meta)
                        }
                    } else base
                tree.emit(
                    sink,
                    nsFilter = listOf(src) + dst,
                    nsRenamer = { renames[it] ?: it }
                )
            }
        }
    }

    /** One `export { }` declaration. */
    abstract class ExportSpec @Inject constructor() {

        /** The output format — a UML format name (`srg`, `tsrg`,
         *  `tinyv2`, `mcpv6method`, `umf`, `proguard`, …). */
        @get:Input
        abstract val type: Property<String>

        /** Side filter for side-aware writers (MCP/AW/AT) — a UML
         *  `EnvType` name; `JOINED` writes everything. */
        @get:Input
        @get:Optional
        abstract val envType: Property<String>

        /** The file to write. */
        @get:OutputFile
        abstract val location: RegularFileProperty

        /** The column to export from. */
        @get:Input
        abstract val sourceNamespace: Property<String>

        /** The column(s) to export to. */
        @get:Input
        abstract val targetNamespaces: ListProperty<String>

        /** Drop javadoc/comment rows from the output (1.x
         *  `skipComments`). */
        @get:Input
        abstract val skipComments: Property<Boolean>

        /** Rename emitted namespace columns — `fromName -> toName`. */
        @get:Input
        abstract val renameNs: MapProperty<String, String>

        init {
            envType.convention("JOINED")
            skipComments.convention(false)
        }

        @get:Internal
        internal val missingFields get() = listOfNotNull(
            "type".takeIf { !type.isPresent },
            "location".takeIf { !location.isPresent },
            "sourceNamespace".takeIf { !sourceNamespace.isPresent },
            "targetNamespaces".takeIf {
                !targetNamespaces.isPresent || targetNamespaces.get().isEmpty()
            }
        )

        internal fun validate() {
            if (missingFields.isNotEmpty()) {
                throw GradleException(
                    "export spec requires ${missingFields.joinToString()} to be set"
                )
            }
        }
    }
}
