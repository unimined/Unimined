package xyz.wagyourtail.unimined.mapping.internal

import xyz.wagyourtail.unimined.api.util.ProgressGate
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.element.Element
import xyz.wagyourtail.unimined.mapping.element.MappingElement
import xyz.wagyourtail.unimined.mapping.element.MetadataElement
import xyz.wagyourtail.unimined.mapping.sink.MappingSink

/**
 * [MappingSink] decorator that counts element pushes — wired to a
 * [ProgressGate] so the row count a reader/emit has processed surfaces in
 * the build log while the merge pipeline is still working.
 */
internal class CountingMappingSink(
    private val delegate: MappingSink,
    private val gate: ProgressGate
) : MappingSink {

    override fun header(namespaces: List<Namespace>) = delegate.header(namespaces)

    override fun push(element: MappingElement): MappingElement? {
        gate.tick()
        return delegate.push(element)
    }

    override fun push(meta: MetadataElement): MetadataElement? {
        gate.tick()
        return delegate.push(meta)
    }

    override fun pop(element: MappingElement) = delegate.pop(element)

    override fun pop(meta: MetadataElement) = delegate.pop(meta)

    override fun pop(element: Element) = delegate.pop(element)

    override fun end() = delegate.end()

}
