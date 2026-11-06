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
     * Binary name of the interface marking a gametest class for this
     * loader (e.g. `net.fabricmc.fabric.api.gametest.v1.FabricGameTest`).
     * When set, the metadata task scans the environment's compiled
     * classes for implementors and passes them to [EmitContext] as
     * [EmitContext.gametestEntrypoints]; `null` disables the scan.
     */
    val gametestInterface: String? get() = null

    /**
     * Binary name of the interface marking a *client-side* gametest
     * class for this loader (e.g.
     * `net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest`).
     * Scanned and emitted exactly like [gametestInterface] but under
     * the loader's client-gametest entrypoint key; `null` disables.
     */
    val clientGametestInterface: String? get() = null

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
     *
     * The reserved path [MANIFEST_PATH] is special: loaders whose mod
     * descriptor IS the jar manifest (manifest-attribute conventions
     * like foxloader's `ModId`/`ModMain`) emit a complete manifest body
     * under it. The task strips that entry from the generated resources
     * and writes it to its manifest-merge output, which the packaging
     * wiring folds into the environment jar's real manifest via
     * `manifest.from(...)`.
     */
    fun emit(context: EmitContext): Map<String, ByteArray>

    companion object {
        /** Reserved [emit] key — manifest merge content, not a file. */
        const val MANIFEST_PATH = "META-INF/MANIFEST.MF"
    }

    /** Everything an emitter needs about one environment. */
    class EmitContext(
        /** Loader-neutral mod model snapshot. */
        val metadata: ModMetadata,
        /** Generated mixin config files for this environment (already
         *  emitted by the host — reference them from loader metadata). */
        val mixinFiles: List<String>,
        /** `*.mixins.json` files found in the environment's resources —
         *  discovered, not generated; emitters list them alongside
         *  [mixinFiles] so hand-written configs are referenced too. */
        val discoveredMixinConfigs: List<String> = emptyList(),
        /** Classes implementing [ModMetadataEmitter.gametestInterface],
         *  discovered by scanning the environment's compiled classes. */
        val gametestEntrypoints: List<String> = emptyList(),
        /** Classes implementing [ModMetadataEmitter.clientGametestInterface],
         *  discovered by the same class scan as [gametestEntrypoints]. */
        val clientGametestEntrypoints: List<String> = emptyList(),
        /**
         * Interface injections derived from the environment's mixin
         * classes — target internal name → implemented interface
         * internal names, in the dev namespace. Emitters may declare
         * them in loader metadata (e.g. `loom:injected_interfaces`);
         * the host also emits them as a classTweaker file referenced
         * through [classTweakerRefs].
         */
        val interfaceInjections: Map<String, List<String>> = emptyMap(),
        /** Jar-relative paths of classTweaker files the host generated
         *  for this environment (interface injections) — emitters
         *  declare them under the loader's `classTweakers` metadata
         *  key so dependents discover them. */
        val classTweakerRefs: List<String> = emptyList(),
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
