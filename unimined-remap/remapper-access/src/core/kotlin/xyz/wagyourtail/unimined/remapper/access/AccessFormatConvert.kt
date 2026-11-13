package xyz.wagyourtail.unimined.remapper.access

import xyz.wagyourtail.commonskt.reader.StringCharReader
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.at.ATReader
import xyz.wagyourtail.unimined.mapping.formats.at.ATWriter
import xyz.wagyourtail.unimined.mapping.formats.at.LegacyATReader
import xyz.wagyourtail.unimined.mapping.formats.at.LegacyATWriter
import xyz.wagyourtail.unimined.mapping.formats.aw.AWReader
import xyz.wagyourtail.unimined.mapping.formats.aw.AWWriter
import xyz.wagyourtail.unimined.mapping.jvms.ext.FullyQualifiedName
import xyz.wagyourtail.unimined.mapping.jvms.four.AccessFlag
import xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.three.two.FieldDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.three.two.ObjectType
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.jvms.four.two.two.UnqualifiedName
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * Cross-format access-file conversion — the 1.x `AccessConvert`
 * (`aw2at`/`at2aw`/`atLegacy2aw`/`mergeAws`) helpers as pure functions.
 * Conversion is namespace-preserving: the emitted document keeps the
 * authored namespace's names — production-jar remapping stays the
 * remap-access pipeline step's job.
 *
 * AT targets carry no member descriptors, so the [Format.AT]→[Format.AW]
 * direction needs a [MappingTree] to resolve field descriptors and
 * expand wildcards (1.x overlaid the AT rows on a copy of the env tree
 * and emitted the joined rows as AW).
 */
object AccessFormatConvert {

    /** Access-file surface formats this converter translates between. */
    enum class Format(val extension: String) {
        AW("accesswidener"),
        AT("cfg"),
        LEGACY_AT("cfg"),
        ;

        companion object {
            fun byName(name: String): Format? = when (name.lowercase()) {
                "aw", "accesswidener" -> AW
                "at", "accesstransformer" -> AT
                "legacyat", "legacy_at" -> LEGACY_AT
                else -> null
            }
        }
    }

    /**
     * Sniff an access file's authored format — filename first, then
     * the first non-comment content line (mirrors remap-access's
     * `kind()` inference — ATs have no header, so a leading access
     * word + [ATFormat] syntax sniff decides AT vs legacy AT);
     * `null` when nothing matches.
     */
    fun detect(path: Path?, bytes: ByteArray): Format? {
        val text by lazy { bytes.toString(Charsets.UTF_8) }
        val name = path?.name?.lowercase()
        if (name != null) {
            if (name.endsWith(".accesswidener")) return Format.AW
            if (name == "accesstransformer.cfg" || name.endsWith("_at.cfg")) {
                return atFormat(text)
            }
        }
        val first = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return null
        return when {
            first.startsWith("accessWidener") -> Format.AW
            first.startsWith("classTweaker") -> null
            // AT rows have no header — the first token is an access
            // word (`public`, `protected-f`, …)
            first.substringBefore(' ')
                .lowercase().removeSuffix("-f") in ACCESS_WORDS ->
                atFormat(text)
            else -> null
        }
    }

    private val ACCESS_WORDS = setOf("public", "protected", "private", "default")

    private fun atFormat(text: String): Format =
        if (xyz.wagyourtail.unimined.mapping.formats.at.ATFormat.detect(text) ==
            xyz.wagyourtail.unimined.mapping.formats.at.ATFormat.Kind.LEGACY
        ) Format.LEGACY_AT else Format.AT

    /**
     * Read [bytes] as [from] and emit in [to]. Unexpressible directives
     * throw (e.g. `mutable class` has no AT form) unless [warn] is set,
     * in which case the row drops with a warning. [tree] is required
     * for AT→AW member descriptor resolution. [targetNs] labels the
     * emitted AW header / drives tree lookups (defaults `"named"`).
     */
    fun convert(
        bytes: ByteArray,
        from: Format,
        to: Format,
        tree: MappingTree? = null,
        targetNs: Namespace? = null,
        warn: ((String) -> Unit)? = null
    ): ByteArray = when {
        from == to -> bytes
        from == Format.AW && (to == Format.AT || to == Format.LEGACY_AT) ->
            aw2at(bytes, legacy = to == Format.LEGACY_AT, warn = warn)
        (from == Format.AT || from == Format.LEGACY_AT) && to == Format.AW ->
            at2aw(
                bytes,
                legacy = from == Format.LEGACY_AT,
                tree = tree ?: throw IllegalArgumentException(
                    "AT→AW conversion requires a mapping tree for " +
                            "member descriptor resolution"
                ),
                targetNs = targetNs ?: Namespace("named"),
                warn = warn
            )
        (from == Format.AT || from == Format.LEGACY_AT) &&
                (to == Format.AT || to == Format.LEGACY_AT) ->
            atRewrite(
                bytes,
                legacyIn = from == Format.LEGACY_AT,
                legacyOut = to == Format.LEGACY_AT
            )
        else -> throw IllegalArgumentException(
            "unsupported access conversion $from → $to"
        )
    }

    fun convert(
        file: Path,
        from: Format,
        to: Format,
        tree: MappingTree? = null,
        targetNs: Namespace? = null,
        warn: ((String) -> Unit)? = null
    ): ByteArray = convert(file.readBytes(), from, to, tree, targetNs, warn)

    private fun warnOrThrow(msg: String, warn: ((String) -> Unit)?) {
        if (warn != null) warn(msg) else throw IllegalArgumentException(msg)
    }

    // ------------------------------------------------------------------
    // AW → AT
    // ------------------------------------------------------------------

    /**
     * Merge accumulator per AT target — visibility collapses to the
     * weakest present (public wins over protected, 1.x's `AccessType`
     * dedup); any `-f` survives.
     */
    private class AtRow {
        var access: AccessFlag = AccessFlag.PRIVATE
        var fin: ATReader.TriState = ATReader.TriState.LEAVE
        fun add(access: AccessFlag, fin: ATReader.TriState) {
            if (VISIBILITY_ORDER.indexOf(access) <
                VISIBILITY_ORDER.indexOf(this.access)) {
                this.access = access
            }
            if (fin == ATReader.TriState.REMOVE) this.fin = fin
        }
    }

    private val VISIBILITY_ORDER = listOf(
        AccessFlag.PUBLIC, AccessFlag.PROTECTED,
        AccessFlag.DEFAULT, AccessFlag.PRIVATE
    )

    /** Identity of the emitted AT row — dedup key for repeated targets. */
    private data class AtKey(
        val cls: InternalName,
        val name: UnqualifiedName?,
        val methodDesc: MethodDescriptor?,
        val isField: Boolean
    ) {
        fun emit(row: AtRow): ATReader.ATData = when {
            name == null -> ATReader.ATDataClass(row.access, row.fin, cls)
            isField -> ATReader.ATDataField(row.access, row.fin, cls, name)
            else -> ATReader.ATDataMethod(
                row.access, row.fin, cls, name, methodDesc!!)
        }
    }

    /**
     * 1.x `aw2at`: `accessible` → public, `extendable` → public (class /
     * field) or protected (method, per `AccessTransformerWriter`) + `-f`,
     * `mutable` (fields only) → `-f` with an implied public (a bare `-f`
     * row isn't valid AT). `transitive-` prefixes drop — AT has no
     * transitive column. Field descriptors drop — AT fields are
     * name-only.
     */
    fun aw2at(
        bytes: ByteArray,
        legacy: Boolean = false,
        warn: ((String) -> Unit)? = null
    ): ByteArray {
        val aw = AWReader.readData(
            StringCharReader(bytes.toString(Charsets.UTF_8)), true)
        val items = mutableListOf<ATReader.ATItem>()
        val rows = HashMap<AtKey, AtRow>()
        val indices = HashMap<AtKey, Int>()
        for (item in aw.targets) {
            when (item) {
                is AWReader.AWComment ->
                    items += ATReader.ATComment(item.comment, item.newline)
                AWReader.AWNewline -> items += ATReader.ATNewline
                is AWReader.AWData -> {
                    val (objType, nad) = item.target.getParts()
                    val cls = objType.getInternalName()
                    val (uname, desc) = nad?.getParts() ?: (null to null)
                    val verb = item.access.removePrefix("transitive-")
                    val kind = when {
                        uname == null -> "class"
                        desc == null || desc.isFieldDescriptor() -> "field"
                        else -> "method"
                    }
                    val pair: Pair<AccessFlag, ATReader.TriState> = when (kind) {
                        "class" -> when (verb) {
                            "accessible" ->
                                AccessFlag.PUBLIC to ATReader.TriState.LEAVE
                            "extendable" ->
                                AccessFlag.PUBLIC to ATReader.TriState.REMOVE
                            else -> {
                                warnOrThrow(
                                    "AW '$verb' on class has no AT form " +
                                            "(only accessible/extendable)",
                                    warn)
                                continue
                            }
                        }
                        "field" -> when (verb) {
                            "accessible" ->
                                AccessFlag.PUBLIC to ATReader.TriState.LEAVE
                            "mutable" ->
                                AccessFlag.PUBLIC to ATReader.TriState.REMOVE
                            else -> {
                                warnOrThrow(
                                    "AW '$verb' on field has no AT form " +
                                            "(only accessible/mutable)",
                                    warn)
                                continue
                            }
                        }
                        else -> when (verb) {
                            "accessible" ->
                                AccessFlag.PUBLIC to ATReader.TriState.LEAVE
                            "extendable" ->
                                AccessFlag.PROTECTED to ATReader.TriState.REMOVE
                            else -> {
                                warnOrThrow(
                                    "AW '$verb' on method has no AT form " +
                                            "(only accessible/extendable)",
                                    warn)
                                continue
                            }
                        }
                    }
                    val key = AtKey(
                        cls, uname,
                        desc?.takeIf { it.isMethodDescriptor() }
                            ?.getMethodDescriptor(),
                        isField = kind == "field"
                    )
                    val idx = indices[key]
                    val row = if (idx == null) {
                        indices[key] = items.size
                        AtRow().also { rows[key] = it }
                    } else {
                        rows[key]!!
                    }
                    row.add(pair.first, pair.second)
                    if (idx == null) items += key.emit(row)
                    else items[idx] = key.emit(row)
                }
            }
        }
        val out = StringBuilder()
        if (legacy) LegacyATWriter.writeData(items) { out.append(it) }
        else ATWriter.writeData(items) { out.append(it) }
        return out.toString().toByteArray()
    }

    // ------------------------------------------------------------------
    // AT → AW
    // ------------------------------------------------------------------

    /**
     * 1.x `at2aw`: public/protected class+method rows → `accessible`
     * (`extendable` when `-f`); fields → `accessible` and/or `mutable`
     * (bare `-f` fields map to `mutable` alone). Member descriptors
     * come from [tree] in [targetNs] — AT rows are desc-less; rows the
     * tree can't resolve drop with a warning. Non-widening rows
     * (private/package visibility, `+f`) have no AW verb and are
     * skipped with a warning.
     */
    fun at2aw(
        bytes: ByteArray,
        legacy: Boolean,
        tree: MappingTree,
        targetNs: Namespace,
        warn: ((String) -> Unit)? = null
    ): ByteArray {
        val text = bytes.toString(Charsets.UTF_8)
        val items = if (legacy) {
            LegacyATReader.readData(StringCharReader(text), leinient = true)
        } else {
            ATReader.readData(StringCharReader(text), leinient = true)
        }
        val targets = mutableListOf<AWReader.AWItem>()

        fun cls(cls: InternalName) =
            FullyQualifiedName(ObjectType(cls), null)

        fun emit(access: String, target: FullyQualifiedName) {
            targets += AWReader.AWData(access, target)
        }

        fun fieldDesc(cls: InternalName, name: UnqualifiedName):
                FieldDescriptor? = tree.run {
            getClass(targetNs, cls)?.getField(targetNs, name, null)
                ?.descs?.get(targetNs)
        }

        fun emitField(cls: InternalName, name: UnqualifiedName,
                      accessible: Boolean, mutable: Boolean) {
            val desc = fieldDesc(cls, name)
            if (desc == null) {
                warn?.invoke("AT field '$cls ${name.value}' has no " +
                        "descriptor in the mapping tree — skipped")
                return
            }
            val fqn = FullyQualifiedName(
                ObjectType(cls), name.withFieldDesc(desc))
            if (accessible) emit("accessible", fqn)
            if (mutable) emit("mutable", fqn)
        }

        fun widening(access: AccessFlag) = access in setOf(
            AccessFlag.PUBLIC, AccessFlag.PROTECTED)

        for (item in items) {
            when (item) {
                is ATReader.ATComment ->
                    targets += AWReader.AWComment(item.comment, item.newline)
                ATReader.ATNewline -> targets += AWReader.AWNewline
                is ATReader.ATDataClass -> {
                    if (!widening(item.access)) {
                        warn?.invoke(
                            "AT '${item.access} ${item.targetClass}' " +
                                    "narrows access — no AW form, skipped")
                    } else if (item.final == ATReader.TriState.REMOVE ||
                        item.access == AccessFlag.PROTECTED) {
                        // class extendable = public + unfinal — the only
                        // AW verb reaching protected-level on classes
                        emit("extendable", cls(item.targetClass))
                    } else {
                        emit("accessible", cls(item.targetClass))
                    }
                }
                is ATReader.ATDataMethod -> {
                    if (!widening(item.access)) {
                        warn?.invoke(
                            "AT '${item.access} ${item.targetClass} " +
                                    "${item.memberName.value}' narrows " +
                                    "access — skipped")
                    } else {
                        val fqn = FullyQualifiedName(
                            ObjectType(item.targetClass), item.member)
                        // method extendable = protected + unfinal —
                        // covers protected grants and +f; `public`
                        // needs accessible since extendable only
                        // reaches protected
                        if (item.access == AccessFlag.PUBLIC) {
                            emit("accessible", fqn)
                        }
                        if (item.final == ATReader.TriState.REMOVE ||
                            item.access == AccessFlag.PROTECTED) {
                            emit("extendable", fqn)
                        }
                    }
                }
                is ATReader.ATDataField -> {
                    val accessible = widening(item.access)
                    val mutable = item.final == ATReader.TriState.REMOVE
                    if (!accessible && !mutable) {
                        warn?.invoke(
                            "AT '${item.access} ${item.targetClass} " +
                                    "${item.memberName.value}' has no " +
                                    "AW form — skipped")
                    } else {
                        emitField(item.targetClass, item.memberName,
                            accessible, mutable)
                    }
                }
                is ATReader.ATDataFieldWildcard -> {
                    val accessible = widening(item.access)
                    val mutable = item.final == ATReader.TriState.REMOVE
                    if (!accessible && !mutable) {
                        warn?.invoke(
                            "AT '${item.access} ${item.targetClass} *' " +
                                    "has no AW form — skipped")
                        continue
                    }
                    val mapping = tree.getClass(targetNs, item.targetClass)
                    if (mapping == null) {
                        warn?.invoke(
                            "AT field wildcard on '${item.targetClass}' " +
                                    "— class not in the mapping tree, " +
                                    "skipped")
                        continue
                    }
                    for (field in mapping.fields) {
                        val name = field.names[targetNs] ?: continue
                        emitField(item.targetClass, name,
                            accessible, mutable)
                    }
                }
                is ATReader.ATDataMethodWildcard -> {
                    val accessible = widening(item.access)
                    val extendable = accessible &&
                            (item.final == ATReader.TriState.REMOVE ||
                                    item.access == AccessFlag.PROTECTED)
                    if (!accessible) {
                        warn?.invoke(
                            "AT '${item.access} ${item.targetClass} " +
                                    "*()' narrows access — skipped")
                        continue
                    }
                    val mapping = tree.getClass(targetNs, item.targetClass)
                    if (mapping == null) {
                        warn?.invoke(
                            "AT method wildcard on '${item.targetClass}' " +
                                    "— class not in the mapping tree, " +
                                    "skipped")
                        continue
                    }
                    for (method in mapping.methods) {
                        val name = method.names[targetNs] ?: continue
                        val desc = method.descs[targetNs] ?: continue
                        if (item.memberDesc != null &&
                            desc != item.memberDesc) continue
                        val fqn = FullyQualifiedName(
                            ObjectType(item.targetClass),
                            name.withMethodDesc(desc))
                        if (item.access == AccessFlag.PUBLIC) {
                            emit("accessible", fqn)
                        }
                        if (extendable) {
                            emit("extendable", fqn)
                        }
                    }
                }
                is ATReader.ATData -> {
                    // future ATData subtypes — nothing else to express
                }
            }
        }
        val out = StringBuilder()
        AWWriter.writeData(
            AWReader.AWMappings(targetNs, targets)
        ) { out.append(it) }
        return out.toString().toByteArray()
    }

    /** AT syntax-era rewrite — both readers produce the same item IR,
     *  so legacy↔modern is parse-and-emit. */
    private fun atRewrite(
        bytes: ByteArray,
        legacyIn: Boolean,
        legacyOut: Boolean
    ): ByteArray {
        val reader = StringCharReader(bytes.toString(Charsets.UTF_8))
        val items = if (legacyIn) LegacyATReader.readData(reader, leinient = true)
        else ATReader.readData(reader, leinient = true)
        val out = StringBuilder()
        if (legacyOut) LegacyATWriter.writeData(items) { out.append(it) }
        else ATWriter.writeData(items) { out.append(it) }
        return out.toString().toByteArray()
    }

    // ------------------------------------------------------------------
    // AW merge
    // ------------------------------------------------------------------

    /**
     * 1.x `mergeAws`: several AW documents into one — rows dedup by
     * (access, target) keeping first occurrence; comments and authored
     * headers drop; the merged document is headed [targetNs].
     */
    fun mergeAws(
        inputs: List<ByteArray>,
        targetNs: Namespace
    ): ByteArray {
        val seen = LinkedHashSet<AWReader.AWData>()
        var version = "v1"
        for (bytes in inputs) {
            val aw = AWReader.readData(
                StringCharReader(bytes.toString(Charsets.UTF_8)), true)
            if (aw.version == "v2") version = "v2"
            for (item in aw.targets) {
                if (item is AWReader.AWData) seen += item
            }
        }
        val out = StringBuilder()
        AWWriter.writeData(
            AWReader.AWMappings(targetNs, seen.toList(), version)
        ) { out.append(it) }
        return out.toString().toByteArray()
    }

}
