package xyz.wagyourtail.unimined.remapper.uml.ext

import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.jvms.four.two.two.UnqualifiedName
import xyz.wagyourtail.unimined.mapping.remapper.ExtensionContext
import xyz.wagyourtail.unimined.mapping.remapper.asm.AsmExtension

/**
 * Pre-mixin LiteLoader (<=1.7.10 era) bakes its lookup tables as string
 * constants rather than bytecode member refs, so a plain ASM remap leaves
 * them in the source namespace and `MethodInfo.matches()` never fires in a
 * target-named runtime:
 *
 *  - `core/runtime/Obf` / `Packets` enums carry per-constant
 *    `(deobfName, obfName)` string args in `<clinit>` — `net.minecraft.…`
 *    dotted class names + obf names for class entries, `func_*`/`field_*`
 *    srg + obf names for member entries. Both columns are rewritten to the
 *    target name so every stored variant resolves at runtime.
 *  - `Methods` + transformer classes pair member constants with their owner
 *    via `new MethodInfo(Obf owner, Obf member, …)` ctor calls — the owner
 *    context needed to map member names lives at the use site, not in the
 *    `Obf` constant itself. `MethodInfo` string-arg forms
 *    (`(Obf,String)`, `(Obf,String,String)`, `(Obf,Obf,String)` and the
 *    `(String,…)` owner-literal variants) carry member names / descriptors
 *    / owner class names as literals that resolve the same way.
 *  - `ClassOverlayTransformer` stub methods/fields carry
 *    `@Obfuscated({srgName, obfName, …})` annotations — name candidates the
 *    runtime tries in order (stub's own name first, then each value) with a
 *    name+desc match against the target class. The shipped alternates cover
 *    srg/notch runtimes only: under a third dev namespace (feather,
 *    mojmap, …) none resolve and overlay application dies with
 *    `Stub method X was not located in the target class` → LiteLoader marks
 *    startup unstable and crashes. Each value string that resolves
 *    unambiguously to a member is rewritten to the target name.
 *
 * Resolution flow: [analyze] collects the `Obf`/`Packets` constant tables
 * and every `MethodInfo` ctor site (owner pairing + descriptor hint built
 * from the ctor's `Object`/`Object[]` type args, remapped to source space
 * through [ExtensionContext.reverse] since use sites may already be
 * target-named under an identity outer remap). [finishAnalysis] resolves
 * each constant's new string args through the source→target remapper —
 * class entries via [ExtensionContext.mapClass], member entries via
 * `mapMethod`/`mapField` on the paired owner — and computes replacements
 * for the literal `MethodInfo` args. [postApply] rewrites the LDCs in the
 * table classes' `<clinit>` and at the `MethodInfo` sites. Anything that
 * fails to resolve stays verbatim (e.g. callback-name strings that were
 * never MC members).
 */
class LiteloaderObfTableExtension(
    private val ctx: ExtensionContext
) : AsmExtension {

    companion object {
        const val OBF_CLASS = "com/mumfrey/liteloader/core/runtime/Obf"
        const val METHOD_INFO = "com/mumfrey/liteloader/transformers/event/MethodInfo"

        /** `com.mumfrey.liteloader.transformers.Obfuscated` — METHOD/FIELD
         *  annotation carrying `String[] value` name-candidate tables */
        const val OBFUSCATED_DESC = "Lcom/mumfrey/liteloader/transformers/Obfuscated;"

        /** table enum classes whose `<clinit>` bakes `(deobf, obf)` string args */
        val TABLE_CLASSES = setOf(
            OBF_CLASS,
            "com/mumfrey/liteloader/core/runtime/Packets",
        )

        private val OBF_TYPE = "L$OBF_CLASS;"
        private val STRING_TYPE = "Ljava/lang/String;"
        private val OBJECT_TYPE = "Ljava/lang/Object;"
        private val OBJECT_ARR = "[Ljava/lang/Object;"

        private val PRIM_TYPE_FIELDS = mapOf(
            "java/lang/Void" to "V", "java/lang/Boolean" to "Z",
            "java/lang/Byte" to "B", "java/lang/Short" to "S",
            "java/lang/Character" to "C", "java/lang/Integer" to "I",
            "java/lang/Long" to "J", "java/lang/Float" to "F",
            "java/lang/Double" to "D",
        )
    }

    /** tokens inside a `new Obf(…)`/`new MethodInfo(…)` ctor window */
    private sealed interface Tok {
        data class ObfRef(val field: String) : Tok
        data class Str(val s: String) : Tok
        data class TypeConst(val type: Type) : Tok
        data class PrimType(val desc: String) : Tok
        /** array-type marker — an `Object[]` arg's element boundary isn't tracked */
        data object Other : Tok
    }

    private data class Site(
        val ctorDesc: String,
        val args: List<Tok>,
        /** LDC nodes in arg order — the mutable handles for postApply rewrites */
        val ldcs: List<LdcInsnNode>,
    )

    /** table class name → (enum field → LDC string args, fns space) */
    private val constants = mutableMapOf<String, MutableMap<String, List<String>>>()

    /** Obf member field → (owner Obf field, obf desc hint) from MethodInfo sites */
    private val memberBindings = mutableMapOf<String, Pair<String, String?>>()

    /** per-class ctor sites carrying literal args (input class name → sites) */
    private val sites = mutableMapOf<String, MutableList<Site>>()

    /** table class → field → resolved new args — built in finishAnalysis */
    private val resolved = mutableMapOf<String, Map<String, List<String>>>()

    /** input class name → ldc value → replacement, built in finishAnalysis */
    private val literalRewrites = mutableMapOf<String, Map<String, String>>()

    /**
     * Owner-free member-name resolution — source-name → target name for every
     * member name in the tree (across all non-target namespaces), kept only
     * when every occurrence of that source name resolves to the SAME target
     * name. `func_*`/`field_*`-style ids are globally unique so they index
     * cleanly; short obf names (`a`, `z`) and common words (`resize`, `run`)
     * collide across members and drop out rather than guess. Values already
     * valid in the target namespace are excluded — a candidate the runtime
     * can already resolve must never be rewritten.
     *
     * Serves both `@Obfuscated` annotation values (name candidates) and
     * [memberNameByUniqueColumn] (Obf-table first columns) — lazily built:
     * jars without liteloader name data pay nothing.
     */
    private val memberNameIndex: Map<String, String> by lazy {
        val index = HashMap<String, String>()
        val conflicts = HashSet<String>()
        val dstNames = HashSet<String>()
        for (cls in ctx.tree.classes) {
            for (m in cls.methods) m.names[ctx.targetNs]?.let { dstNames += it.value }
            for (f in cls.fields) f.names[ctx.targetNs]?.let { dstNames += it.value }
        }
        fun put(src: String, dst: String) {
            if (src == dst || src in dstNames) return
            val prev = index.putIfAbsent(src, dst)
            if (prev != null && prev != dst) conflicts += src
        }
        fun indexNames(names: Map<Namespace, UnqualifiedName>) {
            val dst = names[ctx.targetNs]?.value ?: return
            for ((ns, name) in names) {
                if (ns != ctx.targetNs) put(name.value, dst)
            }
        }
        for (cls in ctx.tree.classes) {
            cls.methods.forEach { indexNames(it.names) }
            cls.fields.forEach { indexNames(it.names) }
        }
        index.keys.removeAll(conflicts)
        index
    }

    override fun analyze(node: ClassNode) {
        if (node.name in TABLE_CLASSES) analyzeTable(node)
        for (m in node.methods) analyzeSites(node.name, m)
    }

    // ---- analyze ----

    /** parse `<clinit>` `new X("a","b") … putstatic FIELD` windows */
    private fun analyzeTable(node: ClassNode) {
        val table = constants.getOrPut(node.name) { mutableMapOf() }
        val clinit = node.methods.firstOrNull { it.name == "<clinit>" } ?: return
        var ctorOwner: String? = null
        var strings = mutableListOf<String>()
        for (insn in clinit.instructions) {
            when (insn) {
                is TypeInsnNode -> if (insn.opcode == org.objectweb.asm.Opcodes.NEW
                    && insn.desc == node.name) {
                    ctorOwner = insn.desc
                    strings = mutableListOf()
                }
                is LdcInsnNode -> if (ctorOwner != null && insn.cst is String) {
                    strings += insn.cst as String
                }
                is MethodInsnNode -> if (ctorOwner != null &&
                    insn.name == "<init>" && insn.owner == ctorOwner) {
                    // args collected; wait for putstatic
                }
                is FieldInsnNode -> if (ctorOwner != null &&
                    insn.opcode == org.objectweb.asm.Opcodes.PUTSTATIC &&
                    insn.owner == node.name && strings.isNotEmpty()) {
                    table[insn.name] = strings
                    ctorOwner = null
                }
            }
        }
    }

    /** parse every `new MethodInfo(…)` ctor window in [method] */
    private fun analyzeSites(
        className: String,
        method: org.objectweb.asm.tree.MethodNode
    ) {
        var collecting = false
        val args = mutableListOf<Tok>()
        val ldcs = mutableListOf<LdcInsnNode>()
        fun flush() { collecting = false; args.clear(); ldcs.clear() }
        for (insn in method.instructions) {
            when {
                insn is TypeInsnNode && insn.opcode == org.objectweb.asm.Opcodes.NEW -> {
                    if (insn.desc == METHOD_INFO) { collecting = true; args.clear(); ldcs.clear() }
                }
                !collecting -> {}
                insn is FieldInsnNode && insn.opcode == org.objectweb.asm.Opcodes.GETSTATIC -> {
                    when {
                        insn.desc == OBF_TYPE ||
                            insn.owner == "com/mumfrey/liteloader/core/runtime/Packets" ->
                            args += Tok.ObfRef(insn.name)
                        insn.name == "TYPE" ->
                            args += (PRIM_TYPE_FIELDS[insn.owner]
                                ?.let { Tok.PrimType(it) } ?: Tok.Other)
                        else -> args += Tok.Other
                    }
                }
                insn is LdcInsnNode -> {
                    ldcs += insn
                    when (val cst = insn.cst) {
                        is String -> args += Tok.Str(cst)
                        is Type -> args += Tok.TypeConst(cst)
                        else -> args += Tok.Other
                    }
                }
                insn is MethodInsnNode && insn.name == "<init>" && insn.owner == METHOD_INFO -> {
                    val site = Site(insn.desc, args.toList(), ldcs.toList())
                    recordSite(className, site)
                    flush()
                }
                // a non-arg instruction inside the window (ALOAD etc.) — arg
                // order in the stream is unaffected, so ignore everything else
            }
        }
    }

    private fun recordSite(className: String, site: Site) {
        val paramTypes = try {
            Type.getArgumentTypes(site.ctorDesc)
        } catch (e: Exception) { return }
        // stream args left→right matching each param's kind
        val obfArgs = mutableListOf<Tok.ObfRef>()
        val strArgs = mutableListOf<Tok.Str>()
        val typeTokens = mutableListOf<Tok>()
        var cursor = 0
        for (param in paramTypes) {
            val desc = param.descriptor
            when {
                desc == OBF_TYPE -> {
                    (site.args.getOrNull(cursor) as? Tok.ObfRef)?.let { obfArgs += it }
                    cursor++
                }
                desc == STRING_TYPE -> {
                    (site.args.getOrNull(cursor) as? Tok.Str)?.let { strArgs += it }
                    cursor++
                }
                desc == OBJECT_TYPE -> {
                    typeTokens += site.args.getOrNull(cursor) ?: Tok.Other
                    cursor++
                }
                desc == OBJECT_ARR -> {
                    // consume the remaining stream as the varargs array
                    typeTokens += site.args.drop(cursor)
                    cursor = site.args.size
                }
            }
        }
        // (Obf owner, Obf member, …) → member binding; the member's obf
        // desc comes from the trailing Object/Object[] type args
        if (obfArgs.size >= 2) {
            val owner = obfArgs[0].field
            val member = obfArgs[1].field
            val desc = buildDesc(typeTokens)
                ?: (strArgs.firstOrNull()?.s?.takeIf { it.startsWith("(") })
            memberBindings[member] = owner to desc
        }
        if (obfArgs.isNotEmpty() || strArgs.isNotEmpty()) {
            sites.getOrPut(className) { mutableListOf() }.add(site)
        }
    }

    /** build an obf-space `(`+args+`)`ret descriptor from Object/Object[]
     *  type tokens — class args arrive as `Obf.X` refs (fns name from the
     *  constant table) or `Type` LDCs (possibly target-named under an
     *  identity outer remap → reverse-mapped to fns) */
    private fun buildDesc(tokens: List<Tok>): String? {
        if (tokens.isEmpty()) return null
        val ret = tokens.first()
        val params = tokens.drop(1)
        fun desc(t: Tok): String? = when (t) {
            is Tok.PrimType -> t.desc
            is Tok.ObfRef -> obfNameOf(t.field)?.let { "L$it;" }
            is Tok.TypeConst -> {
                val ty = t.type
                when (ty.sort) {
                    Type.OBJECT ->
                        "L${ctx.reverse.map(InternalName.unchecked(ty.internalName)).value};"
                    Type.ARRAY -> {
                        // map the innermost element class, keep the dims
                        val dims = ty.descriptor.takeWhile { it == '[' }
                        val el = ty.elementType
                        when (el.sort) {
                            Type.OBJECT -> "$dims L${ctx.reverse.map(InternalName.unchecked(el.internalName)).value};"
                                .replace(" ", "")
                            else -> ty.descriptor
                        }
                    }
                    else -> ty.descriptor
                }
            }
            is Tok.Str -> "L${t.s.replace('.', '/')};"
            Tok.Other -> null
        }
        val p = params.map { desc(it) ?: return null }
        val r = desc(ret) ?: return null
        return "(${p.joinToString("")})$r"
    }

    /** an Obf field's obf (second-arg) name when it's a class constant */
    private fun obfNameOf(field: String): String? {
        for ((_, tbl) in constants) {
            tbl[field]?.let { args ->
                if (args.size >= 2) return args.last()
            }
        }
        return null
    }

    // ---- finishAnalysis ----

    override fun finishAnalysis() {
        // resolve table constants
        for ((cls, table) in constants) {
            val out = mutableMapOf<String, List<String>>()
            for ((field, args) in table) {
                resolveConstant(field, args)?.let { out[field] = it }
            }
            resolved[cls] = out
        }
        // resolve literal MethodInfo args per class
        for ((cls, classSites) in sites) {
            val rw = mutableMapOf<String, String>()
            for (site in classSites) {
                resolveSiteLiterals(site, rw)
            }
            if (rw.isNotEmpty()) literalRewrites[cls] = rw
        }
    }

    /** a table entry's new args — class entries and member entries are both
     *  `(deobf, obf)`; both columns get the target name so whichever variant
     *  `MethodInfo.matches` consults resolves */
    private fun resolveConstant(field: String, args: List<String>): List<String>? {
        if (args.size < 2) {
            // single-arg constants are unobfuscated classes / markers
            // (`<init>`, liteloader/java classes) — remap only if it's a
            // mapped MC class name; non-class literals like `<init>`
            // must skip mapClass (unmapped names get re-rooted under
            // the source tree's default package, e.g. `net/minecraft/src/`)
            val s = args.singleOrNull() ?: return null
            if ('.' !in s && '/' !in s) return null
            val mapped = ctx.mapClass(s.replace('.', '/'))
            return if (mapped != s.replace('.', '/')) listOf(mapped.replace('/', '.')) else null
        }
        val (deobf, obf) = args
        return if ('.' in deobf || '/' in deobf) {
            // class entry — second arg is the obf name; preserve the
            // dotted vs slashed shape of each column
            val mapped = ctx.mapClass(obf)
            if (mapped == obf) return null
            listOf(
                if ('.' in deobf) mapped.replace('/', '.') else mapped,
                mapped,
            )
        } else {
            // member entry — arg0 is the srg (intermediate) name, globally
            // unique across the tree, so it resolves owner-free; fall back
            // to the MethodInfo-paired owner for tables whose first column
            // isn't unique (e.g. mcp-named)
            val mapped = memberNameByUniqueColumn(deobf)
                ?: run {
                    val binding = memberBindings[field] ?: return null
                    val (ownerField, descHint) = binding
                    val owner = obfNameOf(ownerField) ?: return null
                    mapMethodOrField(owner, obf, descHint)
                } ?: return null
            listOf(mapped, mapped)
        }
    }

    /** find a member row by name in any non-target namespace — srg-style
     *  names (`func_*`/`field_*`) are globally unique, so no owner context
     *  is needed; returns the row's target-ns name. Consults
     *  [memberNameIndex]: names that resolve to conflicting targets
     *  (shared obf/mcp names) drop out rather than guess. */
    private fun memberNameByUniqueColumn(name: String): String? =
        memberNameIndex[name]

    /** method-first member resolution on an obf owner+name */
    private fun mapMethodOrField(owner: String, name: String, desc: String?): String? {
        val m = ctx.mapMethod(owner, name, desc)
        if (m != name) return m
        val f = ctx.mapField(owner, name, desc?.takeIf { it.startsWith("L") || it.startsWith("[") })
        if (f != name) return f
        return null
    }

    /** the trailing-literal arg rewrites for one MethodInfo ctor site */
    private fun resolveSiteLiterals(site: Site, out: MutableMap<String, String>) {
        val paramTypes = try {
            Type.getArgumentTypes(site.ctorDesc)
        } catch (e: Exception) { return }
        val obfRefs = site.args.filterIsInstance<Tok.ObfRef>()
        val strs = site.args.filterIsInstance<Tok.Str>()
        var strIdx = 0
        var obfIdx = 0
        for (param in paramTypes) {
            when (param.descriptor) {
                OBF_TYPE -> obfIdx++
                STRING_TYPE -> {
                    val s = strs.getOrNull(strIdx++)?.s ?: continue
                    val isLastString = strIdx == strs.size
                    val ownerObf = obfRefs.getOrNull(0)?.field?.let(::obfNameOf)
                    when {
                        // desc literal — trailing string that parses as a
                        // jvm descriptor gets its class refs remapped
                        s.startsWith("(") ->
                            out[s] = ctx.mapDesc(s, isField = false)
                        s.startsWith("L") && s.endsWith(";") ->
                            out[s] = ctx.mapDesc(s, isField = true)
                        // member-name literal — resolve against the
                        // leading Obf owner if present, else verbatim
                        ownerObf != null ->
                            memberNameViaAnyNs(ownerObf, s)?.let { out[s] = it }
                        // lone string in owner position — class-name literal
                        isLastString && ('.' in s || '/' in s) -> {
                            val mapped = ctx.mapClass(s.replace('.', '/'))
                            if (mapped != s.replace('.', '/')) {
                                out[s] = if ('.' in s) mapped.replace('/', '.') else mapped
                            }
                        }
                    }
                }
            }
        }
    }

    /** a member-name literal authored in any namespace (obf/srg/mcp) →
     *  target name; the owner's class row gives its per-ns name variants */
    private fun memberNameViaAnyNs(ownerObf: String, name: String): String? {
        mapMethodOrField(ownerObf, name, null)?.let { return it }
        val cls = ctx.tree.getClass(ctx.fns, InternalName.unchecked(ownerObf)) ?: return null
        val n = UnqualifiedName.unchecked(name)
        for (ns in ctx.tree.namespaces) {
            if (ns == ctx.fns || ns == ctx.targetNs) continue
            val ownerInNs = cls.names[ns]?.value ?: continue
            val row = ctx.tree.getClass(ns, InternalName.unchecked(ownerInNs)) ?: continue
            with(ctx.tree) {
                row.getMethods(ns, n, null).firstOrNull()
                    ?.let { m -> m.names[ctx.targetNs]?.value?.let { return it } }
                row.getField(ns, n, null)
                    ?.let { f -> f.names[ctx.targetNs]?.value?.let { return it } }
            }
        }
        return null
    }

    // ---- postApply ----

    override fun postApply(
        input: ClassNode,
        output: ClassNode,
        cls: xyz.wagyourtail.unimined.mapping.element.mapping.ClassMapping?
    ) {
        if (input.name in TABLE_CLASSES) rewriteTable(output)
        rewriteSiteLiterals(input.name, output)
        rewriteObfuscatedAnnotations(output)
    }

    /**
     * `@Obfuscated({"srg","notch",…})` on stub methods/fields — the overlay
     * transformer's runtime fallback candidates after the stub's own name.
     * Values that resolve to exactly one member in [memberNameIndex] become
     * the target name so a non-srg/non-mcp dev runtime can locate them;
     * unresolvable or ambiguous values stay verbatim.
     */
    private fun rewriteObfuscatedAnnotations(output: ClassNode) {
        if (memberNameIndex.isEmpty()) return
        fun rewrite(annotations: List<AnnotationNode>?) {
            if (annotations == null) return
            for (ann in annotations) {
                if (ann.desc != OBFUSCATED_DESC) continue
                val values = ann.values ?: continue
                var i = 0
                while (i + 1 < values.size) {
                    if (values[i] == "value") {
                        @Suppress("UNCHECKED_CAST")
                        (values[i + 1] as? MutableList<String>)?.let { list ->
                            for (j in list.indices) {
                                memberNameIndex[list[j]]?.let { list[j] = it }
                            }
                        }
                    }
                    i += 2
                }
            }
        }
        for (m in output.methods) {
            rewrite(m.visibleAnnotations)
            rewrite(m.invisibleAnnotations)
        }
        for (f in output.fields) {
            rewrite(f.visibleAnnotations)
            rewrite(f.invisibleAnnotations)
        }
    }

    /** rewrite the `new Obf(ldc,ldc)` windows in the output table class */
    private fun rewriteTable(node: ClassNode) {
        val table = resolved[node.name] ?: return
        val clinit = node.methods.firstOrNull { it.name == "<clinit>" } ?: return
        var ldcs: MutableList<LdcInsnNode>? = null
        for (insn in clinit.instructions) {
            when (insn) {
                is TypeInsnNode -> if (insn.opcode == org.objectweb.asm.Opcodes.NEW
                    && insn.desc == node.name) {
                    ldcs = mutableListOf()
                }
                is LdcInsnNode -> if (ldcs != null && insn.cst is String) ldcs += insn
                is FieldInsnNode -> if (ldcs != null &&
                    insn.opcode == org.objectweb.asm.Opcodes.PUTSTATIC &&
                    insn.owner == node.name) {
                    table[insn.name]?.let { newArgs ->
                        ldcs.zip(newArgs).forEach { (ldc, v) -> ldc.cst = v }
                    }
                    ldcs = null
                }
            }
        }
    }

    private fun rewriteSiteLiterals(inputName: String, output: ClassNode) {
        val rw = literalRewrites[inputName] ?: return
        for (m in output.methods) {
            for (insn in m.instructions) {
                if (insn is LdcInsnNode && insn.cst is String) {
                    rw[insn.cst]?.let { insn.cst = it }
                }
            }
        }
    }

}
