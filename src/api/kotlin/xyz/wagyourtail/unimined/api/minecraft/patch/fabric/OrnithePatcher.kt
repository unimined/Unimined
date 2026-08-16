package xyz.wagyourtail.unimined.api.minecraft.patch.fabric

/**
 * @since 1.4.2
 */
interface OrnithePatcher : LegacyFabricPatcher {

    /**
     * TODO: make library patching the default in 1.5+
     * @since 1.4.2
     */
    fun enableLibraryPatching()
}