package xyz.wagyourtail.unimined.transform.fabric.internal

import xyz.wagyourtail.unimined.mod.FabricModJsonEmitter

/**
 * `quilt.mod.json` emitter — serves `loader("quilt")` environments.
 * Quilt is a fabric-loader fork, so it consumes the same document
 * shape under its own filename, with quilt-loom's injected-interfaces
 * custom key.
 */
class QuiltModMetadataEmitter : FabricModJsonEmitter() {

    override val loader = "quilt"

    override val metadataFileName = "quilt.mod.json"

    override val injectedInterfacesKey =
        "custom.quilt_loom.injected_interfaces"

}
