package xyz.wagyourtail.unimined.remapper.access

import kotlinx.coroutines.runBlocking
import okio.Buffer
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.NamespaceRemapper
import xyz.wagyourtail.unimined.mapping.element.AddRemove
import xyz.wagyourtail.unimined.mapping.element.metadata.AccessElement
import xyz.wagyourtail.unimined.mapping.element.metadata.EnumExtensionElement
import xyz.wagyourtail.unimined.mapping.element.metadata.InterfaceElement
import xyz.wagyourtail.unimined.mapping.formats.FormatReader
import xyz.wagyourtail.unimined.mapping.formats.FormatReaderSettings
import xyz.wagyourtail.unimined.mapping.formats.at.ATReader
import xyz.wagyourtail.unimined.mapping.formats.aw.AWReader
import xyz.wagyourtail.unimined.mapping.formats.ct.CTReader
import xyz.wagyourtail.unimined.mapping.jvms.four.AccessFlag
import xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.three.two.FieldDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.jvms.four.two.two.UnqualifiedName

/**
 * Parsed AW/CT/AT document — the directive file's declared namespace plus
 * its access rules and structural directives, split by application scope.
 *
 * Backed by UML's readers (`AWReader`/`CTReader`/`ATReader`), so the access
 * rules carry UML's conditional [AccessElement] model rather than a flat
 * set/clear mask — `extendable` only demotes a non-public method to
 * protected, and `accessible` marks previously-private methods final, as
 * the loader specifies.
 *
 * Plain directives apply to the declaring mod only; `transitive-*`
 * directives apply to the declaring mod *and* its dependents — mirroring
 * fabric-loader's semantics and Loom's `TransitiveOnlyFilter`: when a
 * file extracted from a mod dependency is applied to the shared
 * Minecraft compile artifact, only its `transitive-*` rules pass.
 *
 * Structural directives (`inject-interface`, `extend-enum`) are recorded
 * but not yet applied — they are jar transforms, not flag changes
 * (see plan §5 jar processors).
 */
class ClassTweaker private constructor(
    val namespace: String,
    /** the detected format — `aw`, `ct`, or `at` */
    val format: String,
) {

    /** Rule target — a class, or a member keyed by owner/name/desc. */
    sealed interface Target {
        val owner: String
    }

    data class ClassTarget(override val owner: String) : Target
    data class MethodTarget(override val owner: String, val name: String, val desc: String) : Target
    data class FieldTarget(override val owner: String, val name: String, val desc: String) : Target

    /** An `inject-interface`/`transitive-inject-interface` directive. */
    data class InterfaceInjection(val owner: String, val signature: String)

    /** An `extend-enum`/`transitive-extend-enum` directive. */
    data class EnumExtension(val owner: String, val name: String)

    private val access = LinkedHashMap<Target, MutableList<AccessElement>>()
    private val transitiveAccess = LinkedHashMap<Target, MutableList<AccessElement>>()

    /**
     * Structural directives, flagged transitive — recorded verbatim for a
     * future jar-transform consumer (interface injection, enum extension).
     */
    val interfaces = mutableListOf<Pair<InterfaceInjection, Boolean>>()
    val enumExtensions = mutableListOf<Pair<EnumExtension, Boolean>>()

    /**
     * Whether the file declares any `transitive-*` directive. Files from
     * mod dependencies that declare none contribute nothing to dependents
     * and are skipped (Loom's `TransitiveDetectorVisitor` parity).
     */
    val hasTransitiveRules: Boolean
        get() = transitiveAccess.isNotEmpty() ||
                interfaces.any { it.second } ||
                enumExtensions.any { it.second }

    /** Access elements for a class, or `[]`. [transitiveOnly] gives the dependency-application view. */
    fun classChanges(internalName: String, transitiveOnly: Boolean = false): List<AccessElement> =
        lookup(ClassTarget(internalName), transitiveOnly)

    fun methodChanges(
        owner: String, name: String, desc: String, transitiveOnly: Boolean = false
    ): List<AccessElement> = lookup(MethodTarget(owner, name, desc), transitiveOnly)

    fun fieldChanges(
        owner: String, name: String, desc: String, transitiveOnly: Boolean = false
    ): List<AccessElement> = lookup(FieldTarget(owner, name, desc), transitiveOnly)

    private fun lookup(target: Target, transitiveOnly: Boolean): List<AccessElement> =
    // `access` already contains every directive (transitive ones included);
        // `transitiveAccess` is the dependency-application subset
        (if (transitiveOnly) transitiveAccess[target] else access[target]).orEmpty()

    /** All owner classes named by the file (for fast member lookups). */
    val owners: Set<String>
        get() = (access.keys + transitiveAccess.keys +
                interfaces.map { ClassTarget(it.first.owner) } +
                enumExtensions.map { ClassTarget(it.first.owner) }
                ).mapTo(LinkedHashSet()) { it.owner }

    /** Return a copy of this tweaker with every name translated through [remapper]. */
    fun remap(remapper: NamespaceRemapper): ClassTweaker {
        val out = ClassTweaker(namespace, format)
        fun cls(owner: String) = remapper.mapClass(InternalName.unchecked(owner)).value
        fun Target.rebased(): Target = when (this) {
            is ClassTarget -> ClassTarget(cls(owner))
            is MethodTarget -> MethodTarget(
                cls(owner),
                remapper.mapMethod(
                    InternalName.unchecked(owner),
                    UnqualifiedName.unchecked(name),
                    // ATs may omit the descriptor — target by name alone
                    if (desc.isEmpty()) null
                    else MethodDescriptor.unchecked(desc)
                ).value,
                if (desc.isEmpty()) desc
                else remapper.map(MethodDescriptor.unchecked(desc)).toString()
            )

            is FieldTarget -> FieldTarget(
                cls(owner),
                remapper.mapField(
                    InternalName.unchecked(owner),
                    UnqualifiedName.unchecked(name),
                    if (desc.isEmpty()) null
                    else FieldDescriptor.unchecked(desc)
                ).value,
                if (desc.isEmpty()) desc
                else remapper.map(FieldDescriptor.unchecked(desc)).toString()
            )
        }
        for ((target, elements) in access) out.access
            .getOrPut(target.rebased()) { mutableListOf() } += elements
        for ((target, elements) in transitiveAccess) out.transitiveAccess
            .getOrPut(target.rebased()) { mutableListOf() } += elements
        for ((injection, transitive) in interfaces) {
            out.interfaces += injection.copy(
                owner = cls(injection.owner),
                signature = remapSignatureRefs(injection.signature) {
                    remapper.mapClass(InternalName.unchecked(it)).value
                }
            ) to transitive
        }
        for ((extension, transitive) in enumExtensions) {
            out.enumExtensions += extension.copy(owner = cls(extension.owner)) to transitive
        }
        return out
    }

    /**
     * Every declared access rule — plain *and* `transitive-*` — as an
     * [AccessModel] section in this file's own namespace, tagged with
     * [source] provenance. Analysis consumes the complete rule set; the
     * transitive flag is preserved as provenance, not a filter.
     *
     * Conditions on the underlying [AccessElement]s are approximated to
     * set/clear masks — analysis only needs to know the member is
     * reachable, and Loom makes the same approximation for its analyze
     * visitor. Structural directives are not access changes and don't
     * participate (Loom likewise drops enum-extension additions from
     * analysis).
     */
    fun toAccessModelSection(source: String): AccessModel.Section {
        val entries = ArrayList<AccessModel.Entry>()
        fun add(target: Target, elements: List<AccessElement>, transitive: Boolean) {
            if (elements.isEmpty()) return
            var set = 0
            var clear = 0
            for (el in elements) {
                when (el.type) {
                    // visibility/content flags are mutually exclusive in
                    // UML (adding PUBLIC supersedes PRIVATE) — the model's
                    // flat set/clear needs the mask cleared explicitly
                    AddRemove.ADD -> {
                        set = set or el.value.access
                        if (el.value.access and AccessFlag.visibilityMask != 0) {
                            clear = clear or AccessFlag.visibilityMask
                        }
                        if (el.value.access and AccessFlag.contentMask != 0) {
                            clear = clear or AccessFlag.contentMask
                        }
                    }

                    AddRemove.REMOVE -> clear = clear or el.value.access
                }
            }
            val kind = when (target) {
                is ClassTarget -> AccessModel.Kind.CLASS
                is MethodTarget -> AccessModel.Kind.METHOD
                is FieldTarget -> AccessModel.Kind.FIELD
            }
            entries += AccessModel.Entry(
                kind, target.owner,
                when (target) {
                    is MethodTarget -> target.name
                    is FieldTarget -> target.name
                    else -> null
                },
                when (target) {
                    is MethodTarget -> target.desc
                    is FieldTarget -> target.desc
                    else -> null
                },
                AccessModel.Change(set, clear),
                source = source, transitive = transitive
            )
        }
        // `access` holds every directive; `transitiveAccess` the transitive
        // subset — a precise local/transitive split per target by
        // structural equality (AccessElementData is a data class)
        for ((target, elements) in access) {
            val transitive = transitiveAccess[target].orEmpty()
            add(target, elements - transitive.toSet(), false)
            add(target, transitive, true)
        }
        return AccessModel.Section(namespace, entries)
    }

    companion object {

        private fun readTree(
            reader: FormatReader, fileName: String, text: String,
            transitiveOnly: Boolean, nsMapping: Map<String, String>
        ): MappingTree {
            val settings = FormatReaderSettings(
                extras = mapOf(AWReader.ALLOW_NON_TRANSITIVE to !transitiveOnly)
            )
            if (reader === CTReader) {
                // CTReader's transitive filter is the deprecated global
                // var — set/reset for the duration of the read
                synchronized(CTReader) {
                    val prev = CTReader.allowNonTransitive
                    try {
                        CTReader.allowNonTransitive = !transitiveOnly
                        return runBlocking {
                            reader.read(text, nsMapping = nsMapping, settings = settings)
                        }
                    } finally {
                        CTReader.allowNonTransitive = prev
                    }
                }
            }
            return runBlocking {
                reader.read(text, nsMapping = nsMapping, settings = settings)
            }
        }

        /** Detect the directive format from the header and file name. */
        private fun detect(fileName: String, text: String): FormatReader {
            val buffer = Buffer().writeUtf8(text)
            return when {
                CTReader.isFormat(fileName, buffer) -> CTReader
                AWReader.isFormat(fileName, buffer) -> AWReader
                ATReader.isFormat(fileName, buffer) -> ATReader
                else -> throw IllegalArgumentException(
                    "unrecognized access-file format in '$fileName' " +
                            "(expected an accessWidener/classTweaker header or *_at.cfg name)"
                )
            }
        }

        /**
         * Parse an AW/CT/AT document; throws on an unrecognized format.
         *
         * [nsMapping] renames reader-declared namespaces on the way in —
         * headerless AT files declare a literal `source` namespace, so
         * callers pass `mapOf("source" to <real ns>)` when the file's
         * names are known to be in a specific pipeline namespace
         * (dev namespace for user-authored ATs, obf namespace for
         * userdev-extracted ones).
         */
        fun parse(
            fileName: String, text: String,
            nsMapping: Map<String, String> = emptyMap()
        ): ClassTweaker {
            val reader = detect(fileName, text)
            val all = walk(readTree(reader, fileName, text, transitiveOnly = false, nsMapping))
            val transitive = walk(readTree(reader, fileName, text, transitiveOnly = true, nsMapping))
            val tweaker = ClassTweaker(all.namespace ?: transitive.namespace ?: "named", reader.name)
            for ((target, elements) in all.access) {
                tweaker.access.getOrPut(target) { mutableListOf() } += elements
            }
            for ((target, elements) in transitive.access) {
                tweaker.transitiveAccess.getOrPut(target) { mutableListOf() } += elements
            }
            tweaker.interfaces += all.interfaces.map { it to false } +
                    transitive.interfaces.map { it to true }
            tweaker.enumExtensions += all.enumExtensions.map { it to false } +
                    transitive.enumExtensions.map { it to true }
            return tweaker
        }

        private class Walked(
            var namespace: String?,
            val access: MutableMap<Target, MutableList<AccessElement>>,
            val interfaces: MutableList<InterfaceInjection>,
            val enumExtensions: MutableList<EnumExtension>,
        )

        /** Fold a parsed [MappingTree] into target-keyed access elements + structural refs. */
        private fun walk(tree: MappingTree): Walked {
            val namespace = tree.namespaces.singleOrNull()?.name
            val ns = tree.namespaces.singleOrNull()
            val out = Walked(namespace, LinkedHashMap(), mutableListOf(), mutableListOf())
            if (ns == null) return out
            for (cls in tree.classes) {
                val owner = cls.names[ns]?.value ?: continue
                for (meta in cls.metadata) when (meta) {
                    is AccessElement -> out.access
                        .getOrPut(ClassTarget(owner)) { mutableListOf() } += meta

                    is InterfaceElement -> out.interfaces += InterfaceInjection(
                        owner, meta.name.toString()
                    )

                    is EnumExtensionElement -> out.enumExtensions += EnumExtension(
                        owner, meta.name.value
                    )

                    else -> {}
                }
                for (method in cls.methods) {
                    val entry = method.entries[ns] ?: continue
                    for (meta in method.metadata) {
                        if (meta is AccessElement) {
                            out.access.getOrPut(
                                MethodTarget(
                                    owner, entry.name.value,
                                    entry.descriptor?.toString() ?: ""
                                )
                            ) { mutableListOf() } += meta
                        }
                    }
                }
                for (field in cls.fields) {
                    val entry = field.entries[ns] ?: continue
                    for (meta in field.metadata) {
                        if (meta is AccessElement) {
                            out.access.getOrPut(
                                FieldTarget(
                                    owner, entry.name.value,
                                    entry.descriptor?.toString() ?: ""
                                )
                            ) { mutableListOf() } += meta
                        }
                    }
                }
            }
            return out
        }

    }

}

/**
 * Token-wise remap of every `L...` reference inside a signature-ish
 * string (`Lfoo/Bar<TT;>;` or a bare `foo/Bar<TT;>`) — used by
 * `inject-interface` signature rebasing; parsing via
 * `JVMS.parseFieldSignature` rejects the bare form.
 */
internal fun remapSignatureRefs(signature: String, map: (String) -> String): String {
    val wrapped = signature.startsWith('L')
    val start = if (wrapped) 1 else 0
    val end = signature.indexOfFirst { it == '<' || it == ';' || it == '.' }
        .let { if (it >= 0) it else signature.length }
    val withOuter = if (end > start) {
        signature.substring(0, start) +
                map(signature.substring(start, end)) +
                signature.substring(end)
    } else signature
    return Regex("L([^;<.]+)").replace(withOuter) {
        "L${map(it.groupValues[1])}"
    }
}
