package xyz.wagyourtail.unimined.transform.fabric.internal

import xyz.wagyourtail.unimined.mod.FabricModJsonEmitter

/**
 * `fabric.mod.json` emitter — serves `loader("fabric")` environments.
 * Shares the fabric document shape via [FabricModJsonEmitter]; fabric
 * additionally auto-fills `fabric-gametest`/`fabric-client-gametest`
 * entrypoints from classes implementing `FabricGameTest` /
 * `FabricClientGameTest`.
 */
class FabricModMetadataEmitter : FabricModJsonEmitter() {

    override val loader = "fabric"

    override val gametestInterface =
        "net.fabricmc.fabric.api.gametest.v1.FabricGameTest"

    // 1.20-era fabric API: `@GameTest` methods replace the interface
    override val gametestAnnotation =
        "net.fabricmc.fabric.api.gametest.v1.GameTest"

    override val clientGametestInterface =
        "net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest"

}
