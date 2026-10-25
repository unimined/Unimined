package xyz.wagyourtail.unimined.transform.fabric.internal

/**
 * Parsed Access Widener (v1/v2) file: the namespace its names are written
 * in plus the access changes it requests. `transitive-*` directives are
 * recorded but only meaningful to the loader at runtime — jar application
 * ignores them.
 */
internal class AccessWidener(
    val namespace: String
) {

    /** Access change to force on a target: flags to OR in / mask out. */
    class Change(val set: Int, val clear: Int)

    private data class Member(val owner: String, val name: String, val desc: String?)

    private val classes = HashMap<String, Change>()
    private val methods = HashMap<Member, Change>()
    private val fields = HashMap<Member, Change>()

    fun classChange(internalName: String): Change? = classes[internalName]

    fun methodChange(owner: String, name: String, desc: String): Change? =
        methods[Member(owner, name, desc)]
            ?: methods.entries.firstOrNull { it.key.owner == owner && it.key.name == name }?.value

    fun fieldChange(owner: String, name: String, desc: String): Change? =
        fields[Member(owner, name, desc)]
            ?: fields.entries.firstOrNull { it.key.owner == owner && it.key.name == name }?.value

    /** All owner classes named by the file (for fast member lookups). */
    val owners: Set<String>
        get() = (classes.keys + methods.keys.map { it.owner } + fields.keys.map { it.owner }).toSet()

    /** Return a copy of this widener with every name translated through [remapper]. */
    fun remap(remapper: TreeNamespaceRemapper): AccessWidener {
        val out = AccessWidener(namespace)
        for ((owner, change) in classes) {
            out.addClass(remapper.map(owner), change)
        }
        for ((member, change) in methods) {
            out.addMethod(
                remapper.map(member.owner),
                remapper.mapMethod(member.owner, member.name, member.desc),
                member.desc?.let(remapper::mapDesc) ?: "",
                change
            )
        }
        for ((member, change) in fields) {
            out.addField(
                remapper.map(member.owner),
                remapper.mapField(member.owner, member.name, member.desc),
                member.desc?.let(remapper::mapDesc) ?: "",
                change
            )
        }
        return out
    }

    private fun addClass(owner: String, change: Change) {
        classes.merge(owner, change) { a, b -> Change(a.set or b.set, a.clear or b.clear) }
    }

    private fun addMethod(owner: String, name: String, desc: String, change: Change) {
        methods.merge(Member(owner, name, desc), change) { a, b -> Change(a.set or b.set, a.clear or b.clear) }
    }

    private fun addField(owner: String, name: String, desc: String, change: Change) {
        fields.merge(Member(owner, name, desc), change) { a, b -> Change(a.set or b.set, a.clear or b.clear) }
    }

    companion object {

        private const val ACC_PUBLIC = 0x0001
        private const val ACC_PRIVATE = 0x0002
        private const val ACC_PROTECTED = 0x0004
        private const val ACC_FINAL = 0x0010
        private const val ACCESS_MASK = ACC_PRIVATE or ACC_PROTECTED or ACC_PUBLIC

        /** Parse a `*.accesswidener` document; throws on a missing header. */
        fun parse(text: String): AccessWidener {
            var widener: AccessWidener? = null
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(Regex("\\s+"))
                if (widener == null) {
                    require(parts.size >= 3 && parts[0] == "accessWidener") {
                        "access widener must start with 'accessWidener v<N> <namespace>', got: $line"
                    }
                    widener = AccessWidener(parts[2])
                    continue
                }
                widener.directive(parts)
            }
            requireNotNull(widener) { "empty access widener" }
            return widener
        }

        private fun AccessWidener.directive(parts: List<String>) {
            if (parts.size < 3) return
            val transitive = parts[0].startsWith("transitive-")
            val kind = parts[0].removePrefix("transitive-")
            val target = parts[1]
            when (kind) {
                "accessible" -> when (target) {
                    "class" -> apply(parts[2], ACCESS_MASK, ACC_PUBLIC, transitive)
                    "method" -> apply(parts, ACCESS_MASK, ACC_PUBLIC, transitive)
                    "field" -> apply(parts, ACCESS_MASK, ACC_PUBLIC, transitive)
                }
                "extendable" -> when (target) {
                    "class" -> apply(parts[2], ACCESS_MASK or ACC_FINAL, ACC_PUBLIC, transitive)
                    "method" -> apply(parts, ACCESS_MASK or ACC_FINAL, ACC_PROTECTED, transitive)
                }
                "mutable" -> if (target == "field") {
                    apply(parts, ACC_FINAL, 0, transitive)
                }
            }
        }

        private fun AccessWidener.apply(owner: String, clear: Int, set: Int, transitive: Boolean) {
            if (!transitive) addClass(owner, Change(set, clear))
        }

        private fun AccessWidener.apply(
            parts: List<String>,
            clear: Int,
            set: Int,
            transitive: Boolean
        ) {
            if (transitive || parts.size < 5) return
            val (owner, name, desc) = Triple(parts[2], parts[3], parts[4])
            when (parts[1]) {
                "method" -> addMethod(owner, name, desc, Change(set, clear))
                "field" -> addField(owner, name, desc, Change(set, clear))
            }
        }

    }

}
