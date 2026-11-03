package xyz.wagyourtail.unimined.mod.task

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import xyz.wagyourtail.unimined.mod.MixinConfigData
import xyz.wagyourtail.unimined.mod.ModMetadata
import xyz.wagyourtail.unimined.mod.ModMetadataEmitterRegistry
import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter
import java.nio.file.Files

/**
 * Emits generated mod metadata + mixin configs for one bound environment
 * into [outputDir], which the packaging wiring registers as a resources
 * srcDir. Everything materializes at execution: emitters rediscover via
 * ServiceLoader so no loader objects sit in serialized task state.
 *
 * Emitted paths are validated against [resourceDirs] — a manual file at
 * the same path fails the build instead of silently shadowing.
 */
@CacheableTask
abstract class GenerateModMetadataTask : DefaultTask() {

    /** Loader-neutral model snapshot (serializable). */
    @get:Input
    abstract val metadata: Property<ModMetadata>

    /** Generated mixin configs (serializable). */
    @get:Input
    abstract val mixinConfigs: ListProperty<MixinConfigData>

    /** Environment loader name selecting the emitter (`fabric`, ...). */
    @get:Input
    @get:Optional
    abstract val loaderName: Property<String>

    @get:Input
    abstract val environmentId: Property<String>

    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:Input
    abstract val side: Property<String>

    @get:Input
    abstract val devNamespace: Property<String>

    @get:Input
    abstract val prodNamespaces: ListProperty<String>

    /** Existing resource roots — scanned for emitted-path collisions. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourceDirs: ConfigurableFileCollection

    /** Generated output; wired into the source set's resources. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val meta = metadata.get()
        val mixins = mixinConfigs.get()
        val loader = loaderName.orNull

        if (loader == null && meta.sections.isNotEmpty()) {
            throw GradleException(
                "mod { loader(...) } declared on environment " +
                        "'${environmentId.get()}' but the environment declares " +
                        "no loader — the emitted metadata would never attach"
            )
        }
        validate(meta)

        val emitted = linkedMapOf<String, ByteArray>()
        for (mixin in mixins) emitted[mixin.fileName] = mixinJson(mixin)
        // emitters require `id` — an unconfigured `mod {}` in a loader
        // environment emits nothing so a manual metadata file stays
        // authoritative
        if (loader != null && meta.id != null) {
            val emitter = ModMetadataEmitterRegistry().select(loader)
            emitted += emitter.emit(
                ModMetadataEmitter.EmitContext(
                    metadata = meta,
                    mixinFiles = mixins.map { it.fileName },
                    environmentId = environmentId.get(),
                    minecraftVersion = minecraftVersion.get(),
                    side = side.get(),
                    devNamespace = devNamespace.get(),
                    prodNamespaces = prodNamespaces.get()
                )
            )
        }

        checkCollisions(emitted.keys)

        val out = outputDir.get().asFile.toPath()
        emitted.forEach { (path, content) ->
            val target = out.resolve(path)
            Files.createDirectories(target.parent)
            Files.write(target, content)
        }
    }

    private fun validate(meta: ModMetadata) {
        if (meta.sections.isEmpty() && mixinConfigs.get().isEmpty()) return
        val id = meta.id ?: throw GradleException(
            "mod { } declares metadata but no `id` — set `mod.id`"
        )
        require(id.matches(Regex("[a-z][a-z0-9_-]*"))) {
            "mod.id '$id' is invalid — must match [a-z][a-z0-9_-]*"
        }
    }

    private fun checkCollisions(emitted: Set<String>) {
        for (dir in resourceDirs.files) {
            val base = dir.toPath()
            for (path in emitted) {
                val existing = base.resolve(path)
                if (Files.exists(existing)) {
                    throw GradleException(
                        "generated mod metadata '$path' collides with the " +
                                "manual file $existing — keep one source of truth " +
                                "(remove the manual file or drop the generated " +
                                "declaration)"
                    )
                }
            }
        }
    }

    private fun mixinJson(config: MixinConfigData): ByteArray = buildJsonObject {
        put("required", true)
        put("package", config.packageName)
        config.minVersion?.let { put("minVersion", it) }
        config.compatibilityLevel?.let { put("compatibilityLevel", it) }
        config.refmap?.let { put("refmap", it) }
        config.plugin?.let { put("plugin", it) }
        putJsonArray("mixins") { config.mixins.forEach { add(JsonPrimitive(it)) } }
        if (config.client.isNotEmpty()) {
            putJsonArray("client") { config.client.forEach { add(JsonPrimitive(it)) } }
        }
        if (config.server.isNotEmpty()) {
            putJsonArray("server") { config.server.forEach { add(JsonPrimitive(it)) } }
        }
    }.toString().toByteArray()

}
