package xyz.wagyourtail.unimined.mod.spi

import xyz.wagyourtail.unimined.mod.LoaderSection
import xyz.wagyourtail.unimined.mod.ModMetadata
import java.io.Serializable

/**
 * Loader metadata emitter — discovered through `META-INF/services`. One
 * emitter per loader name; the loader transform plugin (e.g.
 * `unimined-transform-fabric`) provides the implementation so the host
 * stays loader-agnostic.
 */
interface ModMetadataEmitter {

    /** Environment loader name this emitter serves (`"fabric"`, ...). */
    val loader: String

    /**
     * The DSL section created for `loader("<name>") { }` — subclass
     * [LoaderSection] for loader-specific fields, or return the base for
     * the shared bag only. Invoked at configuration time; the returned
     * object must stay [Serializable] for task input capture.
     */
    fun createSection(): LoaderSection = LoaderSection()

    /**
     * Emit this loader's metadata files — `path -> contents`. Called at
     * task execution. Return an empty map when the model carries nothing
     * this loader needs.
     */
    fun emit(context: EmitContext): Map<String, ByteArray>

    /** Everything an emitter needs about one environment. */
    class EmitContext(
        /** Loader-neutral mod model snapshot. */
        val metadata: ModMetadata,
        /** Generated mixin config files for this environment (already
         *  emitted by the host — reference them from loader metadata). */
        val mixinFiles: List<String>,
        /** Owning environment id (the source-set name). */
        val environmentId: String,
        /** Environment's Minecraft version. */
        val minecraftVersion: String,
        /** Environment side (`CLIENT`/`SERVER`/`MERGED`). */
        val side: String,
        /** Dev namespace the environment compiles/runs against. */
        val devNamespace: String,
        /** Production namespaces the environment's artifacts remap to. */
        val prodNamespaces: List<String>
    ) : Serializable

}
