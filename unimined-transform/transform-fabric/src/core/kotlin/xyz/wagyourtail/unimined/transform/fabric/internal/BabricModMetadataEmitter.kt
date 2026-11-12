package xyz.wagyourtail.unimined.transform.fabric.internal

import xyz.wagyourtail.unimined.mod.FabricModJsonEmitter

/**
 * `fabric.mod.json` emitter — serves `loader("babric")` environments.
 * Babric is a fabric-loader fork for beta MC, so it consumes the same
 * `fabric.mod.json` document shape. No gametest interface — babric's
 * fabric-api vintage predates the gametest module.
 */
class BabricModMetadataEmitter : FabricModJsonEmitter() {

    override val loader = "babric"

}
