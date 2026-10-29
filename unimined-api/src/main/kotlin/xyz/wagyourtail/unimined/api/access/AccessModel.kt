package xyz.wagyourtail.unimined.api.access

import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.NamespaceRemapper
import xyz.wagyourtail.unimined.mapping.jvms.ext.FieldOrMethodDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.three.two.FieldDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.jvms.four.two.two.UnqualifiedName
import xyz.wagyourtail.unimined.mapping.remapper

/**
 * The remap-analysis access overlay: the normalized union of AW/AT/CT
 * access rules declared by the artifacts in a remap batch, fed to a
 * `remap` engine's analysis pass so widened members participate in
 * inheritance/member propagation (Tiny Remapper's `extraAnalyzeVisitor`
 * role; UML's `AsmExtension.analyze` equivalent).
 *
 * Rules are normalized to `acc' = (acc & ~clear) | set` masks so every
 * declaring format (AW accessible/extendable/mutable, AT modifiers, CT
 * equivalents) collapses onto one shape. Entries are grouped into
 * [sections] by the namespace their names are written in — access-rule
 * files declare their own namespace, so a batch may mix them; [rules]
 * resolves one namespace's view, rebasing foreign sections through a
 * [MappingTree] when one is available.
 *
 * Each entry carries optional [Entry.source] provenance (the declaring
 * artifact/file) and a [Entry.transitive] flag, preserved for
 * diagnostics — analysis itself consumes all rules regardless.
 *
 * The serialized form is a small line-oriented document
 * (`unimined-access v1`) so it can travel through transform parameters
 * and task inputs without a task dependency.
 */
class AccessModel(
    val sections: List<Section>
) {

    /**
     * A rule section whose names are written in [namespace] — typically
     * one per declaring artifact/declaration source.
     */
    class Section(
        val namespace: String,
        val entries: List<Entry>
    )

    /** One normalized access rule. */
    class Entry(
        val kind: Kind,
        /** Internal name of the owning class (the class itself for [Kind.CLASS]). */
        val owner: String,
        /** Member name for [Kind.METHOD]/[Kind.FIELD]; `null` for classes. */
        val name: String? = null,
        /** Member descriptor; `null` for classes or desc-agnostic rules. */
        val desc: String? = null,
        val change: Change,
        /** Provenance — declaring artifact/file, for diagnostics. */
        val source: String? = null,
        /** Whether the declaring file marked this rule `transitive`. */
        val transitive: Boolean = false
    )

    enum class Kind { CLASS, METHOD, FIELD }

    /** `acc' = (acc & ~clear) | set`. */
    class Change(val set: Int, val clear: Int) {
        operator fun plus(other: Change) = Change(set or other.set, clear or other.clear)
    }

    /** Whether the model carries any rules at all. */
    val isEmpty: Boolean get() = sections.all { it.entries.isEmpty() }

    /**
     * Lookup view over the rules of one namespace — built by [rules].
     * Member lookups fall back to a name-only match when the exact
     * descriptor isn't in the model, matching widener semantics.
     */
    class Rules internal constructor(entries: List<Entry>) {

        private data class Member(val owner: String, val name: String, val desc: String?)

        private val classes = HashMap<String, Change>()
        private val methods = HashMap<Member, Change>()
        private val fields = HashMap<Member, Change>()

        init {
            for (entry in entries) {
                when (entry.kind) {
                    Kind.CLASS -> classes.merge(entry.owner, entry.change, Change::plus)
                    Kind.METHOD -> methods.merge(
                        Member(entry.owner, entry.name ?: continue, entry.desc),
                        entry.change, Change::plus
                    )

                    Kind.FIELD -> fields.merge(
                        Member(entry.owner, entry.name ?: continue, entry.desc),
                        entry.change, Change::plus
                    )
                }
            }
        }

        val isEmpty: Boolean
            get() = classes.isEmpty() && methods.isEmpty() && fields.isEmpty()

        fun classChange(internalName: String): Change? = classes[internalName]

        fun methodChange(owner: String, name: String, desc: String): Change? =
            lookup(methods, owner, name, desc)

        fun fieldChange(owner: String, name: String, desc: String): Change? =
            lookup(fields, owner, name, desc)

        private fun lookup(
            table: Map<Member, Change>, owner: String, name: String, desc: String
        ): Change? = table[Member(owner, name, desc)]
            ?: table.entries.firstOrNull {
                it.key.owner == owner && it.key.name == name
            }?.value

    }

    /**
     * The rule view for [namespace]: sections already written in it merge
     * directly; foreign-namespace sections rebase through [tree] when it
     * knows both namespaces (otherwise they are skipped — an absent tree
     * cannot map them).
     */
    fun rules(namespace: String, tree: MappingTree? = null): Rules =
        Rules(entries(namespace, tree))

    /**
     * The normalized rule entries for [namespace] — same section
     * resolution and rebasing as [rules], for consumers that need the
     * entries themselves rather than a lookup view.
     */
    fun entries(namespace: String, tree: MappingTree? = null): List<Entry> {
        val entries = ArrayList<Entry>()
        for (section in sections) {
            when {
                section.namespace == namespace -> entries += section.entries
                tree != null -> {
                    val src = tree.namespaces.firstOrNull { it.name == section.namespace }
                        ?: continue
                    val dst = tree.namespaces.firstOrNull { it.name == namespace }
                        ?: continue
                    val remapper = tree.remapper(src, dst)
                    entries += section.entries.map { it.rebase(remapper) }
                }
            }
        }
        return entries
    }

    private fun Entry.rebase(remapper: NamespaceRemapper): Entry {
        val owner = InternalName.unchecked(this.owner)
        // AT-style targets can carry an empty descriptor — pass null so
        // the lookup matches by name alone
        val desc = desc?.takeIf { it.isNotEmpty() }
        return Entry(
            kind,
            remapper.mapClass(owner).value,
            name?.let {
                when (kind) {
                    Kind.METHOD -> remapper.mapMethod(
                        owner,
                        UnqualifiedName.unchecked(it),
                        desc?.let { d -> MethodDescriptor.unchecked(d) }
                    ).value

                    Kind.FIELD -> remapper.mapField(
                        owner,
                        UnqualifiedName.unchecked(it),
                        desc?.let { d -> FieldDescriptor.unchecked(d) }
                    ).value

                    Kind.CLASS -> it
                }
            },
            // descriptors carry class refs — remap them through the same view
            desc?.let { remapper.map(FieldOrMethodDescriptor.unchecked(it)).toString() },
            change, source, transitive
        )
    }

    /**
     * Serialize to the `unimined-access v1` line format — deterministic
     * (section/entry order preserved) so it is safe as a transform/task
     * input fingerprint.
     */
    fun serialize(): String = buildString {
        appendLine("unimined-access v1")
        for (section in sections) {
            appendLine("section ${section.namespace}")
            var lastSource: String? = null
            for (entry in section.entries) {
                if (entry.source != lastSource && entry.source != null) {
                    appendLine("source ${entry.source}")
                    lastSource = entry.source
                }
                val transitive = if (entry.transitive) " transitive" else ""
                when (entry.kind) {
                    Kind.CLASS -> appendLine(
                        "class ${entry.owner} ${entry.change.set} ${entry.change.clear}$transitive"
                    )
                    // desc-less targets (AT fields name no descriptor)
                    // carry `-` — an empty token would collapse under
                    // whitespace splitting
                    Kind.METHOD -> appendLine(
                        "method ${entry.owner} ${entry.name} ${entry.desc?.ifEmpty { "-" } ?: "-"} " +
                                "${entry.change.set} ${entry.change.clear}$transitive"
                    )

                    Kind.FIELD -> appendLine(
                        "field ${entry.owner} ${entry.name} ${entry.desc?.ifEmpty { "-" } ?: "-"} " +
                                "${entry.change.set} ${entry.change.clear}$transitive"
                    )
                }
            }
        }
    }

    companion object {

        /** The empty model — serializes to the bare header. */
        val EMPTY = AccessModel(emptyList())

        /** Merge models by concatenating their sections. */
        fun merge(models: Iterable<AccessModel>): AccessModel =
            AccessModel(models.flatMap { it.sections })

        /** Parse the `unimined-access v1` line format. */
        fun parse(text: String): AccessModel {
            val sections = ArrayList<Section>()
            var namespace: String? = null
            var source: String? = null
            val entries = ArrayList<Entry>()
            fun flush() {
                val ns = namespace ?: return
                sections += Section(ns, entries.toList())
                entries.clear()
            }
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0]) {
                    "unimined-access" -> require(parts.size >= 2 && parts[1] == "v1") {
                        "unsupported access model header: $line"
                    }

                    "section" -> {
                        flush()
                        namespace = parts[1]
                        source = null
                    }

                    "source" -> source = line.removePrefix("source").trim()
                    "class" -> entries += entry(Kind.CLASS, parts, source)
                    "method" -> entries += entry(Kind.METHOD, parts, source)
                    "field" -> entries += entry(Kind.FIELD, parts, source)
                    else -> throw IllegalArgumentException(
                        "malformed access model line: $line"
                    )
                }
            }
            flush()
            return AccessModel(sections)
        }

        private fun entry(kind: Kind, parts: List<String>, source: String?): Entry {
            val transitive = parts.lastOrNull() == "transitive"
            return when (kind) {
                Kind.CLASS -> Entry(
                    kind, parts[1],
                    change = Change(parts[2].toInt(), parts[3].toInt()),
                    source = source, transitive = transitive
                )

                Kind.METHOD, Kind.FIELD -> Entry(
                    kind, parts[1], parts[2],
                    parts[3].let { if (it == "-") "" else it },
                    Change(parts[4].toInt(), parts[5].toInt()),
                    source = source, transitive = transitive
                )
            }
        }

    }

}
