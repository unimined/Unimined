package xyz.wagyourtail.unimined.transform.fabric.internal

import xyz.wagyourtail.unimined.mod.FabricModJsonEmitter

/**
 * `fabric.mod.json` emitter — serves `loader("ornithe")` environments.
 * Ornithe is a fabric-loader fork for legacy MC, so it consumes the same
 * `fabric.mod.json` document shape. No gametest interface — ornithe's
 * fabric-api vintage predates the gametest module.
 */
class OrnitheModMetadataEmitter : FabricModJsonEmitter() {

    override val loader = "ornithe"

}
