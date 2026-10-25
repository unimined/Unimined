package xyz.wagyourtail.unimined.api.mapping

/**
 * Typed [MappingData] view exposing the full `UniminedMappingLibrary`
 * [MappingTree] — constant groups, `BytecodeTransform` elements, and other
 * metadata the portable [MappingTree]/[MappingVisitor] interchange cannot
 * carry. Required by UML-backed steps (e.g. `unimined-remapper-uml`);
 * `null` when the resolved mapping data isn't UML-sourced.
 */
interface UmlMappingTreeView : MappingDataView {

    val tree: xyz.wagyourtail.unimined.mapping.MappingTree

}
