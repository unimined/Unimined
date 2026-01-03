package xyz.wagyourtail.unimined.internal.mapping.aw
//
//import kotlinx.coroutines.runBlocking
//import net.fabricmc.accesswidener.AccessWidenerReader
//import net.fabricmc.accesswidener.AccessWidenerVisitor
//import net.fabricmc.tinyremapper.TinyRemapper
//import xyz.wagyourtail.unimined.api.mapping.MappingsConfig
//import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
//import xyz.wagyourtail.unimined.mapping.Namespace
//
///**
// * @param delegate      The visitor to forward the remapped information to.
// * @param toNamespace   The namespace that the access widener will be remapped to.
// */
//class AccessWidenerBetterRemapper(
//    private val delegate: AccessWidenerVisitor,
//    private val mappingsProvider: MappingsConfig<*>,
//    private val toNamespace: String,
//    private val mcProvider: MinecraftConfig
//): AccessWidenerVisitor {
//    private var remapper: TinyRemapper? = null
//
//    override fun visitHeader(namespace: String) = runBlocking {
//        if (namespace != toNamespace) {
//            remapper = TinyRemapper.newRemapper()
//                .withMappings(
//                    mappingsProvider.getTRMappings(
//                        Namespace(namespace) to Namespace(toNamespace),
//                        false
//                    )
//                ).build()
//
//            remapper?.readClassPathAsync(*mcProvider.minecraftLibraries.resolve().map { it.toPath() }.toTypedArray())
//            remapper?.readClassPathAsync(
//                mcProvider.getMinecraft(
//                    Namespace(namespace)
//                )
//            )
//        }
//
//        delegate.visitHeader(toNamespace)
//    }
//
//    override fun visitClass(name: String, access: AccessWidenerReader.AccessType, transitive: Boolean) {
//        delegate.visitClass(
//            if (remapper != null) remapper!!.environment.remapper.map(name) else name,
//            access,
//            transitive
//        )
//    }
//
//    override fun visitMethod(
//        owner: String,
//        name: String,
//        descriptor: String,
//        access: AccessWidenerReader.AccessType,
//        transitive: Boolean
//    ) {
//        delegate.visitMethod(
//            if (remapper != null) remapper!!.environment.remapper.map(owner) else owner,
//            if (remapper != null) remapper!!.environment.remapper.mapMethodName(owner, name, descriptor) else name,
//            if (remapper != null) remapper!!.environment.remapper.mapDesc(descriptor) else descriptor,
//            access,
//            transitive
//        )
//    }
//
//    override fun visitField(
//        owner: String,
//        name: String,
//        descriptor: String,
//        access: AccessWidenerReader.AccessType,
//        transitive: Boolean
//    ) {
//        delegate.visitField(
//            if (remapper != null) remapper!!.environment.remapper.map(owner) else owner,
//            if (remapper != null) remapper!!.environment.remapper.mapFieldName(owner, name, descriptor) else name,
//            if (remapper != null) remapper!!.environment.remapper.mapDesc(descriptor) else descriptor,
//            access,
//            transitive
//        )
//    }
//}