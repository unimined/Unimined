package xyz.wagyourtail.unimined.minecraft

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import xyz.wagyourtail.unimined.api.util.delegatingTo
import javax.inject.Inject

/**
 * Inline access-declaration DSL behind `access { }` — rules are stored
 * semantically (kind + member + descriptor), not as file text, so the
 * generated document can be emitted in whichever format the env's
 * loader consumes: `accessWidener`/`classTweaker` on AW-family loaders,
 * FML `accesstransformer.cfg` on AT-family. Directives with no
 * equivalent in the consumed format are dropped with a build-log note
 * (interface injection/enum extension have no AT analog).
 *
 * Declared rules ride the same channel as file-declared access files —
 * applied to the dev Minecraft artifact, shipped in the mod jar (the
 * `accessWidener` field on fabric-family / `FMLAT` manifest on forge),
 * and propagated to aux environments through `combineWith`.
 */
open class AccessDslSpec @Inject constructor() {

    /** Declared rules, in declaration order — plumbing view for env
     *  materialization (emit per consumed format); not part of the
     *  DSL surface. */
    val rules: List<Rule> get() = _rules
    internal val _rules = mutableListOf<Rule>()

    /**
     * Jar path this env's merged access document ships at — overrides
     * the loader's conventional canonical path
     * (`{modid}.accesswidener`, `META-INF/accesstransformer.cfg`) where
     * the loader's metadata declares the path; `{modid}` expands. All
     * `access { }` calls' rules merge into ONE canonical doc, so
     * setting this twice with different paths is a config error (for
     * multiple shipped files use `from(path) { output = ... }`).
     * Ignored where the loader has no access-file convention.
     */
    var fileName: String? = null
        set(value) {
            val prev = field
            if (prev != null && value != null && prev != value)
                throw org.gradle.api.GradleException(
                    "access doc already named '$prev' — cannot also " +
                            "name it '$value' (rules from all access { } " +
                            "calls merge into one document; for multiple " +
                            "shipped files use from(path) { output = ... })")
            field = value
        }

    /**
     * Format this env's merged access document is *emitted* in —
     * `"aw"`/`"accesswidener"`, `"at"`/`"accesstransformer"`, or
     * `"legacy_at"`/`"legacyat"`; `classTweaker` isn't settable (the
     * doc upgrades to CT automatically when rules require it and the
     * loader parses it). Default: the loader's consumed format.
     * Overriding means the canonical file is written in that format —
     * the user is responsible for the loader/toolchain actually
     * parsing it.
     */
    var format: String? = null
        set(value) {
            val prev = field
            if (prev != null && value != null && prev != value)
                throw org.gradle.api.GradleException(
                    "access doc format already '$prev' — cannot also " +
                            "set '$value'")
            field = value
        }

    /** `from(...)` file declarations — routed by detected format into
     *  `accessWideners`/`accessTransformers` at materialization. */
    val files: List<Any> get() = _files
    internal val _files = mutableListOf<Any>()

    /** `from(path) { }` declarations with packaging options — ride the
     *  env's `accessFileOverrides` channel (custom output/declare/
     *  format), not the format-routed collections. */
    val namedFiles: List<NamedFrom> get() = _namedFiles
    internal val _namedFiles = mutableListOf<NamedFrom>()

    /** One `from(path) { }` file with packaging options — mirrors
     *  [xyz.wagyourtail.unimined.api.spec.DeclaredAccessFile]'s shape:
     *  [format] forces source interpretation, [output] sets a custom
     *  jar path, [declare] toggles the loader-metadata reference. */
    data class NamedFrom(
        val path: Any,
        val format: String?,
        val output: String?,
        val declare: Boolean,
    )

    /** A single semantic access rule — emitted per consumed format. */
    sealed class Rule {
        /** `accessible`/`extendable`/`mutable` (+ `transitive-` prefix). */
        data class Access(
            val directive: Directive,
            val transitive: Boolean,
            val kind: Kind,
            val owner: String,
            val member: String?,
            val desc: String?,
        ) : Rule()

        /** `inject-interface`/`transitive-inject-interface`. */
        data class InjectInterface(
            val transitive: Boolean,
            val owner: String,
            val signature: String,
        ) : Rule()

        /** `extend-enum`/`transitive-extend-enum`. */
        data class ExtendEnum(
            val transitive: Boolean,
            val owner: String,
            val constant: String,
        ) : Rule()

        /** Raw FML AT entry — `access[-f] class [member [desc]]`. */
        data class AtEntry(
            val access: String,
            val owner: String,
            val member: String?,
            val desc: String?,
        ) : Rule()
    }

    enum class Directive { ACCESSIBLE, EXTENDABLE, MUTABLE }
    enum class Kind { CLASS, METHOD, FIELD }

    // --- AW/CT access directives ----------------------------------------

    /** `accessible class <className>` — opens the class for reference. */
    fun accessible(className: String) =
        access(Directive.ACCESSIBLE, false, Kind.CLASS, className)

    /** `accessible <method|field>` — kind inferred from [desc]. */
    fun accessible(owner: String, member: String, desc: String) =
        access(Directive.ACCESSIBLE, false, kindOf(desc), owner, member, desc)

    /** `transitive-accessible` twins — propagate to dependents. */
    fun transitiveAccessible(className: String) =
        access(Directive.ACCESSIBLE, true, Kind.CLASS, className)

    fun transitiveAccessible(owner: String, member: String, desc: String) =
        access(Directive.ACCESSIBLE, true, kindOf(desc), owner, member, desc)

    /** `extendable` — removes final, allows subclassing/implementing. */
    fun extendable(className: String) =
        access(Directive.EXTENDABLE, false, Kind.CLASS, className)

    fun extendable(owner: String, member: String, desc: String) =
        access(Directive.EXTENDABLE, false, kindOf(desc), owner, member, desc)

    fun transitiveExtendable(className: String) =
        access(Directive.EXTENDABLE, true, Kind.CLASS, className)

    fun transitiveExtendable(owner: String, member: String, desc: String) =
        access(Directive.EXTENDABLE, true, kindOf(desc), owner, member, desc)

    /** `mutable` — removes final, allows writes. */
    fun mutable(className: String) =
        access(Directive.MUTABLE, false, Kind.CLASS, className)

    fun mutable(owner: String, member: String, desc: String) =
        access(Directive.MUTABLE, false, kindOf(desc), owner, member, desc)

    fun transitiveMutable(className: String) =
        access(Directive.MUTABLE, true, Kind.CLASS, className)

    fun transitiveMutable(owner: String, member: String, desc: String) =
        access(Directive.MUTABLE, true, kindOf(desc), owner, member, desc)

    // --- CT-only directives ----------------------------------------------

    /** `inject-interface` — target class gains the interface at the
     *  class level (dev artifact + shipped CT for dependents). */
    fun injectInterface(owner: String, signature: String) {
        _rules += Rule.InjectInterface(false, owner, signature)
    }

    fun transitiveInjectInterface(owner: String, signature: String) {
        _rules += Rule.InjectInterface(true, owner, signature)
    }

    /** `extend-enum` — adds a constant (`classTweaker v2`, loader >=
     *  0.19.0 on fabric). */
    fun extendEnum(owner: String, constant: String) {
        _rules += Rule.ExtendEnum(false, owner, constant)
    }

    fun transitiveExtendEnum(owner: String, constant: String) {
        _rules += Rule.ExtendEnum(true, owner, constant)
    }

    // --- AT entries -------------------------------------------------------

    /** Raw FML AT entry — `<access>[-f] <class> [member [desc]]`
     *  (`at("public", "com/example/Foo")`,
     *  `at("public-f", "com/example/Bar", "baz", "I")`). */
    fun at(access: String, className: String, member: String? = null, desc: String? = null) {
        _rules += Rule.AtEntry(access, className, member, desc)
    }

    // --- existing files ----------------------------------------------------

    /**
     * Declare existing access files — the document format is detected
     * per file (`accessWidener`/`classTweaker` headers, FML AT name
     * conventions) and routed to the matching env collection, exactly
     * as if it were passed to `accessWideners.from(...)`/
     * `accessTransformers.from(...)` directly. Accepts anything
     * `project.files` resolves (paths, `File`, providers).
     */
    fun from(vararg paths: Any) {
        _files.addAll(paths)
    }

    /** [from] with per-file packaging control — the file converts and
     *  emits at [NamedFrom.output] instead of folding into the
     *  canonical merge, with optional `format` forcing and `declare`
     *  metadata toggle (same options as `accessFile(path) { }`). */
    fun from(
        path: Any,
        action: org.gradle.api.Action<MinecraftEnvironment.AccessFileSpec>
    ) {
        val spec = MinecraftEnvironment.AccessFileSpec()
        action.execute(spec)
        _namedFiles += NamedFrom(path, spec.format, spec.output, spec.declare)
    }

    /** Groovy DSL twin of [from]. */
    fun from(
        path: Any,
        @DelegatesTo(
            value = MinecraftEnvironment.AccessFileSpec::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        val spec = MinecraftEnvironment.AccessFileSpec()
        action.delegatingTo(spec)
        _namedFiles += NamedFrom(path, spec.format, spec.output, spec.declare)
    }

    private fun access(
        directive: Directive, transitive: Boolean, kind: Kind,
        owner: String, member: String? = null, desc: String? = null,
    ) {
        _rules += Rule.Access(directive, transitive, kind, owner, member, desc)
    }

    private fun kindOf(desc: String): Kind =
        if (desc.startsWith("(")) Kind.METHOD else Kind.FIELD

    // --- emission ---------------------------------------------------------

    /** The consumed document format for an env. */
    enum class Format { AW, CT, AT }

    /** Whether any rule needs the `classTweaker` document shape
     *  (interface injection/enum extension vs plain AW directives). */
    val needsClassTweaker: Boolean
        get() = rules.any { it is Rule.InjectInterface || it is Rule.ExtendEnum }

    /**
     * Emit the collected rules as file lines in [format] — no header,
     * no trailing newline. AW directives emit their declared form on
     * AW/CT; on AT they map to the closest FML entry (`accessible` →
     * `public`, `extendable` → `protected-f`, `mutable` → `public-f` —
     * the AT model couples access+finality, so `mutable` widens to
     * public as a side effect). CT directives and AT entries only emit
     * on their native formats; cross-format rules return in
     * [dropped] for the caller to log.
     */
    fun emit(format: Format, dropped: MutableList<String> = mutableListOf()): List<String> {
        val out = mutableListOf<String>()
        for (rule in rules) when (rule) {
            is Rule.Access -> when (format) {
                Format.AT -> out += atLine(rule)
                else -> out += awLine(rule)
            }
            is Rule.InjectInterface -> if (format == Format.AT) {
                dropped += "inject-interface ${rule.owner} ${rule.signature}"
            } else out += "${if (rule.transitive) "transitive-" else ""}inject-interface\t${rule.owner}\t${rule.signature}"
            is Rule.ExtendEnum -> if (format == Format.AT) {
                dropped += "extend-enum ${rule.owner} ${rule.constant}"
            } else out += "${if (rule.transitive) "transitive-" else ""}extend-enum\t${rule.owner}\t${rule.constant}"
            is Rule.AtEntry -> if (format == Format.AT) out += atEntryLine(rule)
                else out += atEntryToAw(rule) ?: run {
                    dropped += "at ${rule.access} ${rule.owner}" +
                            (rule.member?.let { " $it" } ?: "")
                    emptyList()
                }
        }
        return out
    }

    private fun awLine(r: Rule.Access): String {
        val d = (if (r.transitive) "transitive-" else "") + r.directive.name.lowercase()
        return if (r.kind == Kind.CLASS) "$d\tclass\t${r.owner}"
        else "$d\t${r.kind.name.lowercase()}\t${r.owner}\t${r.member}\t${r.desc}"
    }

    private fun atLine(r: Rule.Access): String {
        val access = when (r.directive) {
            Directive.ACCESSIBLE -> "public"
            Directive.EXTENDABLE -> "protected-f"
            Directive.MUTABLE -> "public-f"
        }
        return "$access ${r.owner}${memberSuffix(r.member, r.desc)}"
    }

    private fun atEntryLine(r: Rule.AtEntry): String =
        "${r.access} ${r.owner}${memberSuffix(r.member, r.desc)}"

    /**
     * Map an AT entry onto AW directives — `public`/`protected` widen
     * to `accessible`, the `-f` suffix adds `extendable` (class/method)
     * or `mutable` (field). Narrowing access words (`private`,
     * `default`) and member rows without a descriptor (AW member rules
     * require one) can't be expressed — `null` drops them.
     */
    private fun atEntryToAw(r: Rule.AtEntry): List<String>? {
        val unfinal = r.access.endsWith("-f")
        val widens = r.access.removeSuffix("-f") in setOf("public", "protected")
        if (!widens) return null
        if (r.member != null && r.desc == null) return null
        val kind = when {
            r.member == null -> Kind.CLASS
            else -> kindOf(r.desc!!)
        }
        val target = if (kind == Kind.CLASS) "class\t${r.owner}"
            else "${kind.name.lowercase()}\t${r.owner}\t${r.member}\t${r.desc}"
        return buildList {
            add("accessible\t$target")
            if (unfinal) add(
                "${if (kind == Kind.FIELD) "mutable" else "extendable"}\t$target"
            )
        }
    }

    private fun memberSuffix(member: String?, desc: String?): String =
        if (member == null) "" else " $member${if (desc != null) " $desc" else ""}"
}
