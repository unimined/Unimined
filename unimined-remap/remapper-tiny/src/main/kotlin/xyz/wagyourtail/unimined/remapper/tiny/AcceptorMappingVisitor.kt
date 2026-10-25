package xyz.wagyourtail.unimined.remapper.tiny

import net.fabricmc.tinyremapper.IMappingProvider
import xyz.wagyourtail.unimined.api.mapping.MappingVisitor

/**
 * Bridges the api's [MappingVisitor] interchange onto tiny-remapper's
 * [IMappingProvider.MappingAcceptor].
 */
internal class AcceptorMappingVisitor(
    private val acceptor: IMappingProvider.MappingAcceptor
) : MappingVisitor {

    override fun visitClass(srcName: String, dstName: String) {
        acceptor.acceptClass(srcName, dstName)
    }

    override fun visitField(srcClass: String, srcName: String, srcDesc: String?, dstName: String) {
        acceptor.acceptField(IMappingProvider.Member(srcClass, srcName, srcDesc), dstName)
    }

    override fun visitMethod(srcClass: String, srcName: String, srcDesc: String?, dstName: String) {
        acceptor.acceptMethod(IMappingProvider.Member(srcClass, srcName, srcDesc), dstName)
    }

    override fun visitMethodArg(srcClass: String, srcMethod: String, srcDesc: String?, index: Int, dstName: String) {
        acceptor.acceptMethodArg(IMappingProvider.Member(srcClass, srcMethod, srcDesc), index, dstName)
    }

    override fun visitMethodVar(srcClass: String, srcMethod: String, srcDesc: String?, lvIndex: Int, dstName: String) {
        acceptor.acceptMethodVar(IMappingProvider.Member(srcClass, srcMethod, srcDesc), lvIndex, -1, -1, dstName)
    }

}
