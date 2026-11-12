package xyz.wagyourtail.unimined.transform.fabric.internal

import xyz.wagyourtail.unimined.mod.FabricModJsonEmitter

/**
 * `fabric.mod.json` emitter — serves `loader("legacyfabric")`
 * environments. Legacy Fabric is a fabric-loader fork for legacy MC,
 * so it consumes the same `fabric.mod.json` document shape. No
 * gametest interface — legacyfabric's fabric-api vintage predates the
 * gametest module.
 */
class LegacyFabricModMetadataEmitter : FabricModJsonEmitter() {

    override val loader = "legacyfabric"

}
