package xyz.wagyourtail.unimined.provider.native.metadata

import kotlinx.serialization.json.JsonObject
import xyz.wagyourtail.unimined.minecraft.spi.LaunchMetadata

/** Launch metadata backed by Mojang's raw version JSON documents. */
class NativeLaunchMetadata(
    private val rootDocument: () -> JsonObject,
    private val loadParent: (String) -> JsonObject
) : LaunchMetadata {

    private val rootValue: JsonObject by lazy(LazyThreadSafetyMode.SYNCHRONIZED, rootDocument)

    override val root: JsonObject
        get() = rootValue

    /** Load a parent version document for `inheritsFrom` resolution. */
    override fun parent(id: String): JsonObject = loadParent(id)

}
