package xyz.wagyourtail.unimined.remapper.uml

import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.element.mapping.ClassMapping
import xyz.wagyourtail.unimined.mapping.element.AddRemove
import xyz.wagyourtail.unimined.mapping.element.metadata.AccessElement
import xyz.wagyourtail.unimined.mapping.jvms.ext.condition.AccessConditions
import xyz.wagyourtail.unimined.mapping.jvms.four.AccessFlag
import xyz.wagyourtail.unimined.mapping.jvms.four.ElementType
import xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.three.two.FieldDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.jvms.four.two.two.UnqualifiedName

/**
 * The remap-analysis overlay for the UML engine — `AsmExtension.analyze`
 * runs after the inheritance graph is scanned, so access changes cannot
 * be folded in there. UML's equivalent is [AccessElement] metadata on
 * mapping-tree member rows: `MemberGroups.propagate` calls
 * `InheritanceTree.applyAccessChanges`, which folds declared access
 * changes into the scanned member flags before propagation resolves
 * names — a widened member then behaves like ordinary inheritance.
 *
 * This builds a synthetic single-namespace overlay tree carrying those
 * elements; it merges into the real trees in `AsmRemapper.remap`'s
 * `trees` list (metadata accumulates across layers; the identity-named
 * fns entries stamp nothing new).
 */
internal fun accessModelOverlay(
    entries: List<AccessModel.Entry>,
    namespace: Namespace,
    reference: MappingTree? = null
): MutableMappingTree {
    val tree = MutableMappingTree(setOf(namespace))
    for (entry in entries) {
        val cls = tree.clazz(
            mapOf(namespace to InternalName.unchecked(entry.owner))
        )
        val type = when (entry.kind) {
            AccessModel.Kind.METHOD -> ElementType.METHOD
            AccessModel.Kind.FIELD -> ElementType.FIELD
            AccessModel.Kind.CLASS -> ElementType.CLASS
        }
        val elements = accessElements(entry.change, type)
        val name = entry.name
        when (entry.kind) {
            AccessModel.Kind.METHOD -> {
                if (name == null) continue
                val member = cls.method(
                    mapOf(
                        namespace to UnqualifiedName.unchecked(name)
                            .withMethodDesc(
                                entry.desc?.let { MethodDescriptor.unchecked(it) })
                    )
                )
                elements.forEach(member::addMetadata)
                // desc-agnostic rules (ATs write desc-less targets)
                // also stamp every matching member row the real
                // mapping tree knows — the elements otherwise orphan
                if (entry.desc.isNullOrEmpty()) {
                    referenceClass(reference, namespace, entry.owner)
                        ?.methods
                        ?.filter { it.names[namespace]?.value == name }
                        ?.forEach { member ->
                            member.descs[namespace]?.let { desc ->
                                cls.method(
                                    mapOf(namespace to UnqualifiedName
                                        .unchecked(name).withMethodDesc(desc))
                                ).apply { elements.forEach(::addMetadata) }
                            }
                        }
                }
            }

            AccessModel.Kind.FIELD -> {
                if (name == null) continue
                val member = cls.field(
                    mapOf(
                        namespace to UnqualifiedName.unchecked(name)
                            .withFieldDesc(
                                entry.desc?.let { FieldDescriptor.unchecked(it) })
                    )
                )
                elements.forEach(member::addMetadata)
                if (entry.desc.isNullOrEmpty()) {
                    referenceClass(reference, namespace, entry.owner)
                        ?.fields
                        ?.filter { it.names[namespace]?.value == name }
                        ?.forEach { member ->
                            member.descs[namespace]?.let { desc ->
                                cls.field(
                                    mapOf(namespace to UnqualifiedName
                                        .unchecked(name).withFieldDesc(desc))
                                ).apply { elements.forEach(::addMetadata) }
                            }
                        }
                }
            }

            AccessModel.Kind.CLASS -> elements.forEach(cls::addMetadata)
        }
    }
    return tree
}

/** The reference tree's class row for `owner`, or null. */
private fun referenceClass(
    reference: MappingTree?,
    namespace: Namespace,
    owner: String
): ClassMapping? = reference?.classes?.firstOrNull {
    it.names[namespace]?.value == owner
}

/**
 * Translate a set/clear mask into per-flag [AccessElement]s — removes
 * first, then adds, matching `acc' = (acc & ~clear) | set` (set wins).
 */
private fun accessElements(
    change: AccessModel.Change, type: ElementType
): List<AccessElement> = buildList {
    for (flag in AccessFlag.of(type, change.clear)) {
        add(AccessElement(AddRemove.REMOVE, flag, AccessConditions.ALL))
    }
    for (flag in AccessFlag.of(type, change.set)) {
        add(AccessElement(AddRemove.ADD, flag, AccessConditions.ALL))
    }
}
