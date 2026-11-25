package xyz.wagyourtail.unimined.remapper.uml.ext

import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import xyz.wagyourtail.unimined.mapping.element.mapping.ClassMapping
import xyz.wagyourtail.unimined.mapping.remapper.ExtensionContext
import xyz.wagyourtail.unimined.mapping.remapper.asm.AsmExtension

/**
 * `ldc` string constants carrying class refs — invisible to the remap
 * engine's type-ref pass, which only touches real bytecode references.
 * Coremod transformers hardcode these literals to construct injected
 * calls at runtime (unimixins' `EnhanceCrashReportsTransformer` loads
 * `(Lnet/minecraft/crash/CrashReport;Lnet/minecraft/crash/
 * CrashReportCategory;)V` verbatim and hands it to ASM `Type`/desc
 * parsing) — left in the source namespace, the injected code references
 * classes that don't exist on a dev-named jar → `NoClassDefFoundError`.
 *
 * Two literal shapes are rewritten in [postApply]:
 *  - bare internal names — `net/minecraft/crash/CrashReportCategory`
 *    (slash form only: dotted literals collide with ordinary text too
 *    easily to guess at)
 *  - descriptor literals — `(L…;…)R` / `L…;` / `[L…;`
 *
 * Gate: a literal is only rewritten when the source→target map actually
 * changes a name inside it — the `ctx.mapClass`/`ctx.mapDesc` result is
 * the authority, so log messages, paths and other coincidental strings
 * survive verbatim.
 */
class ClassNameLdcExtension(
    private val ctx: ExtensionContext,
    /** source-namespace internal class names this pass is scoped to */
    private val classes: Set<String>
) : AsmExtension {

    companion object {
        /** `a/b/C` internal name — ≥1 slash, java-identifier segments */
        private val INTERNAL_NAME = Regex("[\\w$]+(/[\\w$]+)+")
        /** method descriptor literal — `(<args>)<ret>` */
        private val METHOD_DESC = Regex("\\([\\w/$;\\[]*\\)[\\w/$;\\[]+")
        /** field/type descriptor literal — `[`-dims then `L…;` */
        private val TYPE_DESC = Regex("\\[*L[\\w/$]+;")
    }

    override fun postApply(
        input: ClassNode,
        output: ClassNode,
        cls: ClassMapping?
    ) {
        if (input.name !in classes) return
        for (m in output.methods) {
            for (insn in m.instructions) {
                if (insn is LdcInsnNode && insn.cst is String) {
                    rewrite(insn.cst as String)?.let { insn.cst = it }
                }
            }
        }
    }

    /** the literal's rewritten form, or `null` to leave it verbatim —
     *  a shape that only *looks* like a descriptor (`(foo)bar` regex
     *  hits) is left alone when the desc parser rejects it */
    private fun rewrite(s: String): String? = try {
        when {
            // method descriptor — `(args)ret` form
            METHOD_DESC.matches(s) -> {
                val mapped = ctx.mapDesc(s, isField = false)
                if (mapped != s) mapped else null
            }
            // type descriptor — `L…;` or `[L…;` form
            TYPE_DESC.matches(s) -> {
                val mapped = ctx.mapDesc(s, isField = true)
                if (mapped != s) mapped else null
            }
            // bare internal name
            INTERNAL_NAME.matches(s) -> {
                val mapped = ctx.mapClass(s)
                if (mapped != s) mapped else null
            }
            else -> null
        }
    } catch (e: IllegalArgumentException) {
        null
    }
}
