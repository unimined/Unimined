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

    /**
     * Fabric loader `0.18.0` added classTweaker support — CT-format
     * content is parsed through the declared `accessWidener` file
     * (`extend-enum` additionally needs `0.19.0`, enforced by the
     * loader at parse time). Unpinned versions stay AW-only — a
     * CT-shaped canonical doc would hard-fail truly-old loaders.
     */
    override fun accessDocCtCapable(loaderVersion: String?): Boolean {
        val m = CT_MIN_VERSION.find(loaderVersion ?: return false)
            ?: return false
        val major = m.groupValues[1].toInt()
        val minor = m.groupValues[2].toInt()
        return major > 0 || minor >= 18
    }

    private companion object {
        val CT_MIN_VERSION = Regex("""(\d+)\.(\d+)\.\d+""")
    }

}
