package xyz.wagyourtail.unimined.transform.conversion.internal

import xyz.wagyourtail.unimined.mod.FabricModJsonEmitter

/**
 * `fabric.mod.json` emitter — serves `loader("bta-babric")` environments.
 * Babric is a fabric-loader fork for Beta 1.7.3, so its mod metadata is
 * the same `fabric.mod.json` document Fabric emits; only the registered
 * loader name differs. No gametest interface — Beta has no gametest API.
 */
class BtaBabricModMetadataEmitter : FabricModJsonEmitter() {

    override val loader = "bta-babric"

}
