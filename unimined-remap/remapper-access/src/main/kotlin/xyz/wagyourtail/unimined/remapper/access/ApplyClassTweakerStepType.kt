package xyz.wagyourtail.unimined.remapper.access

import org.gradle.api.GradleException
import org.objectweb.asm.*
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.element.metadata.AccessElement
import xyz.wagyourtail.unimined.mapping.jvms.four.AccessFlag
import xyz.wagyourtail.unimined.mapping.jvms.four.ElementType
import xyz.wagyourtail.unimined.mapping.remapper
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.FusedJarEntry
import xyz.wagyourtail.unimined.remap.spi.FusibleJarStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.reflect.KClass

/**
 * `apply-class-tweaker` implementation backed by ASM. For every declared
 * AW/CT/AT file it rebases the directives onto the pipeline's target
 * namespace and adjusts the access flags of matching classes, methods,
 * and fields through UML's conditional [AccessElement] model.
 */
class ApplyClassTweakerStepType : RemapStepType<ApplyClassTweakerStepSpec> {

    override val name = "access"
    override val stepName = "apply-class-tweaker"
    override val specType: KClass<ApplyClassTweakerStepSpec> = ApplyClassTweakerStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.JAR)

    override val needsMappingTree = true

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: ApplyClassTweakerStepSpec, ctx: StepContext): ArtifactStep =
        object : FusibleJarStep {
            private var translated: List<ClassTweaker> = emptyList()
            private var depTranslated: List<ClassTweaker> = emptyList()
            private val visited = Visited()
            private var declared: List<Pair<String, ClassTweaker.Target>> = emptyList()
            private var validate = false

            override fun prepareFused(input: Path, c: StepContext) {
                val files = spec.classTweakerFiles.files.filter { it.isFile }
                val depFiles = spec.dependencyClassTweakerFiles.files.filter { it.isFile }
                if (files.isEmpty() && depFiles.isEmpty()) return

                val tree = c.mappings?.view<MappingTree>()
                    ?: throw GradleException(
                        "apply-class-tweaker requires mapping data exposing a MappingTree view"
                    )

                // `source` = the anonymous namespace of headerless AT
                // files — the spec binds it to the ns the file's names
                // are actually in
                val nsMapping = spec.headerlessNamespace.orNull
                    ?.let { mapOf("source" to it) } ?: emptyMap()
                val tweakers = files.map {
                    it.name to ClassTweaker.parse(it.name, it.readText(), nsMapping)
                }
                val depTweakers = depFiles.map {
                    it.name to ClassTweaker.parse(it.name, it.readText(), nsMapping)
                }
                val (t, d) = translate(
                    tweakers.map { it.second },
                    depTweakers.map { it.second },
                    tree, c.spec.targetNamespace,
                    spec.namespaceAliases.getOrElse(emptyMap())
                )
                translated = t; depTranslated = d

                // `validate` — declared targets (already rebased)
                // that must each match a visited class/member.
                // Own declarations only: structural directives
                // (inject-interface/extend-enum) owe a class target
                // alongside the access rules.
                validate = spec.validate.orNull == true
                if (validate) {
                    declared = (tweakers zip translated).flatMap {
                        (named, tt) ->
                        tt.accessTargets.map { named.first to it } +
                                tt.interfaces.map {
                                    named.first to
                                            ClassTweaker.ClassTarget(it.first.owner)
                                } +
                                tt.enumExtensions.map {
                                    named.first to
                                            ClassTweaker.ClassTarget(it.first.owner)
                                }
                    }
                }
            }

            override fun visitEntry(entry: FusedJarEntry, c: StepContext): FusedJarEntry {
                if (translated.isEmpty() && depTranslated.isEmpty()) return entry
                if (!entry.name.endsWith(".class")) return entry
                return FusedJarEntry(
                    entry.name,
                    applyToClass(
                        entry.bytes, translated, depTranslated,
                        if (validate) visited else null
                    )
                )
            }

            override fun endFused(c: StepContext) {
                if (!validate || declared.isEmpty()) return
                val unmatched = declared.filter { (_, target) ->
                    !visited.matches(target)
                }
                if (unmatched.isEmpty()) return
                val lines = unmatched.take(20).joinToString("\n") { (src, t) ->
                    "  $src: $t"
                }
                throw GradleException(
                    "apply-class-tweaker validation failed — " +
                            "${unmatched.size} declared access rule(s) " +
                            "matched nothing:\n$lines" +
                            (if (unmatched.size > 20)
                                "\n  ... and ${unmatched.size - 20} more"
                            else "")
                )
            }
        }

    /**
     * Standalone validation core — parse [files] (display-name to
     * doc), rebase onto [targetNamespace], visit every class in [jar]
     * and return the declared targets that matched nothing. Shares the
     * fused step's translation + member-matching semantics exactly
     * (`access { validate }` at remap time); the `validate<Env>Access`
     * task wraps it without running a remap (loom
     * `ValidateAccessWidenerTask` parity).
     */
    internal fun validateDocs(
        files: List<Pair<String, java.io.File>>,
        sourceNsMapping: Map<String, String>,
        tree: MappingTree,
        targetNamespace: String,
        namespaceAliases: Map<String, String>,
        jar: Path
    ): List<Pair<String, ClassTweaker.Target>> {
        val tweakers = files.filter { it.second.isFile }.map { (name, f) ->
            name to ClassTweaker.parse(name, f.readText(), sourceNsMapping)
        }
        if (tweakers.isEmpty()) return emptyList()
        val (translated, _) = translate(
            tweakers.map { it.second }, emptyList(),
            tree, targetNamespace, namespaceAliases
        )
        // own declarations only: structural directives
        // (inject-interface/extend-enum) owe a class target alongside
        // the access rules — same set the fused step's `validate` tracks
        val declared = (tweakers zip translated).flatMap { (named, tt) ->
            tt.accessTargets.map { named.first to it } +
                    tt.interfaces.map {
                        named.first to ClassTweaker.ClassTarget(it.first.owner)
                    } +
                    tt.enumExtensions.map {
                        named.first to ClassTweaker.ClassTarget(it.first.owner)
                    }
        }
        if (declared.isEmpty()) return emptyList()
        val visited = Visited()
        JarFile(jar.toFile()).use { j ->
            for (entry in j.entries()) {
                if (!entry.isDirectory && entry.name.endsWith(".class")) {
                    applyToClass(
                        j.getInputStream(entry).readBytes(),
                        translated, emptyList(), visited
                    )
                }
            }
        }
        return declared.filter { (_, t) -> !visited.matches(t) }
    }

    /** Visited member sets collected during the class pass for
     *  `validateAccessTransformers` — `\0`-joined keys keep the sets
     *  flat (desc-less targets match on owner+name alone). */
    internal class Visited {
        val classes = mutableSetOf<String>()
        val methodsByName = mutableSetOf<String>()
        val methodsByDesc = mutableSetOf<String>()
        val fieldsByName = mutableSetOf<String>()
        val fieldsByDesc = mutableSetOf<String>()

        fun matches(t: ClassTweaker.Target): Boolean = when (t) {
            is ClassTweaker.ClassTarget -> t.owner in classes
            is ClassTweaker.MethodTarget ->
                "${t.owner}\u0000${t.name}\u0000${t.desc}" in methodsByDesc ||
                        (t.desc.isEmpty() &&
                                "${t.owner}\u0000${t.name}" in methodsByName)
            is ClassTweaker.FieldTarget ->
                "${t.owner}\u0000${t.name}\u0000${t.desc}" in fieldsByDesc ||
                        (t.desc.isEmpty() &&
                                "${t.owner}\u0000${t.name}" in fieldsByName)
            is ClassTweaker.MethodWildcardTarget ->
                methodsByDesc.any { key ->
                    key.startsWith("${t.owner}\u0000") &&
                            (t.desc.isEmpty() || key.endsWith("\u0000${t.desc}"))
                }
            is ClassTweaker.FieldWildcardTarget ->
                fieldsByDesc.any { key ->
                    key.startsWith("${t.owner}\u0000") &&
                            (t.desc.isEmpty() || key.endsWith("\u0000${t.desc}"))
                }
        }
    }

    /** Rebase each file's directives onto the target namespace so
     *  bytecode members can be looked up under their own names. */
    internal fun translate(
        tweakers: List<ClassTweaker>,
        dependencyTweakers: List<ClassTweaker>,
        tree: MappingTree,
        targetNamespace: String,
        namespaceAliases: Map<String, String> = emptyMap()
    ): Pair<List<ClassTweaker>, List<ClassTweaker>> {
        fun translate(tweaker: ClassTweaker) =
            if (tweaker.namespace == targetNamespace) tweaker
            else {
                val src = tree.namespaces.firstOrNull { it.name == tweaker.namespace }
                    ?: namespaceAliases[tweaker.namespace]
                        ?.let { alias ->
                            tree.namespaces.firstOrNull { it.name == alias }
                                // gen-suffixed prod columns (`calamusGen2`)
                                // share the channel name's prefix
                                ?: tree.namespaces.firstOrNull { it.name.startsWith(alias) }
                        }
                    ?: throw GradleException(
                        "apply-class-tweaker: file namespace '${tweaker.namespace}' " +
                                "is not in the mapping tree " +
                                "(namespaces: ${tree.namespaces.joinToString { it.name }}; " +
                                "aliases: $namespaceAliases)"
                    )
                val dst = tree.namespaces.firstOrNull { it.name == targetNamespace }
                    ?: throw GradleException(
                        "apply-class-tweaker: target namespace '$targetNamespace' " +
                                "is not in the mapping tree"
                    )
                tweaker.remap(tree.remapper(src, dst))
            }

        return tweakers.map(::translate) to dependencyTweakers.map(::translate)
    }

    internal fun apply(
        input: Path,
        output: Path,
        tweakers: List<ClassTweaker>,
        dependencyTweakers: List<ClassTweaker>,
        tree: MappingTree,
        targetNamespace: String,
        namespaceAliases: Map<String, String> = emptyMap()
    ) {
        val (translated, depTranslated) = translate(
            tweakers, dependencyTweakers, tree, targetNamespace, namespaceAliases
        )

        JarFile(input.toFile()).use { jar ->
            JarOutputStream(Files.newOutputStream(output)).use { out ->
                // first-wins dedupe — real-world artifacts ship duplicate
                // entries (mojang's realms jars); zip readers resolve the
                // first match, so that copy is the one worth keeping
                val seen = mutableSetOf<String>()
                for (entry in jar.entries()) {
                    if (!seen.add(entry.name)) continue
                    val next = JarEntry(entry.name)
                    next.time = entry.time
                    out.putNextEntry(next)
                    if (!entry.isDirectory) {
                        val bytes = jar.getInputStream(entry).readBytes()
                        out.write(
                            if (entry.name.endsWith(".class")) {
                                applyToClass(bytes, translated, depTranslated)
                            } else bytes
                        )
                    }
                    out.closeEntry()
                }
            }
        }
    }

    /** `inject-interface` signature → the internal name for the
     *  `interfaces` array (`Lfoo/Bar<TT;>;` or bare `foo/Bar<TT;>`). */
    private fun interfaceInternal(signature: String): String =
        signature.removePrefix("L")
            .substringBefore('<').removeSuffix(";")

    /** Wrap a signature-ish string as a `L...;` class-type-signature for
     *  appending to a class's `Signature` attribute. */
    private fun wrap(signature: String): String =
        (if (signature.startsWith('L')) signature else "L$signature")
            .let { if (it.endsWith(';')) it else "$it;" }

    /** The class `Signature` after appending [injected] — JVMS
     *  ClassSignature lists the superclass, then every superinterface.
     *  A non-generic class carries none, so one is only synthesized when
     *  an injected interface is generic. */
    private fun injectedSignature(
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
        injected: List<String>
    ): String? {
        if (injected.isEmpty()) return signature
        if (signature == null && injected.none { '<' in it }) return null
        val base = signature ?: buildString {
            append(wrap(superName ?: "java/lang/Object"))
            interfaces?.forEach { append(wrap(it)) }
        }
        return injected.fold(base) { sig, inj -> sig + wrap(inj) }
    }

    internal fun applyToClass(
        bytes: ByteArray,
        tweakers: List<ClassTweaker>,
        depTweakers: List<ClassTweaker>,
        visited: Visited? = null
    ): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, 0)
        var className = ""
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {

            override fun visit(
                version: Int, access: Int, name: String,
                signature: String?, superName: String?, interfaces: Array<out String>?
            ) {
                className = name
                visited?.classes?.add(name)
                // an interface the class already declares (or one several
                // docs inject) must not be listed twice in the signature
                val injected = injectedSignatures(name)
                    .filter { interfaceInternal(it) !in interfaces.orEmpty() }
                    .distinctBy(::interfaceInternal)
                val merged = (interfaces?.toList().orEmpty() +
                        injected.map(::interfaceInternal)
                        ).toTypedArray()
                super.visit(
                    version,
                    // `$`-named directives are inner-class targets — the
                    // modifier belongs on the InnerClasses entries
                    // (visitInnerClass below), never on the class
                    // header. Forge's InnerClassTarget leaves
                    // ClassNode.access untouched; merging e.g.
                    // PROTECTED into the header clears ACC_PUBLIC and
                    // turns the class package-private to the JVM's
                    // verify_class_access (MappedRegistry$TagSet /
                    // NamespacedWrapper IllegalAccessError)
                    if ('$' in name) access
                    else adjust(access, ElementType.CLASS) { t, d -> t.classChanges(name, d) },
                    name,
                    injectedSignature(signature, superName, interfaces, injected),
                    superName, merged
                )
            }

            override fun visitInnerClass(
                name: String, outerName: String?, innerName: String?, access: Int
            ) {
                // Forge AT semantics: `Outer$Inner` class directives
                // (InnerClassTarget) merge into the InnerClassNode
                // entries whose name matches — the outer's declaration
                // and the inner's self-entry both carry the flags —
                // and plain class targets (ClassTarget) merge into
                // their self-entry as well. Applying at every matching
                // entry keeps declarations consistent regardless of
                // which class file declares them.
                super.visitInnerClass(
                    name, outerName, innerName,
                    adjust(access, ElementType.INNER_CLASS) { t, d -> t.classChanges(name, d) }
                )
            }

            /**
             * `inject-interface` signatures targeting [name] — own files
             * contribute all directives, dependency files only
             * `transitive-inject-interface` (same partition as access).
             */
            private fun injectedSignatures(name: String): List<String> =
            // transitive directives appear as both (inj,false) — the
                // local role — and (inj,true) — dependency propagation
                tweakers.flatMap { t ->
                    t.interfaces.filter { it.first.owner == name && !it.second }
                        .map { it.first.signature }
                } + depTweakers.flatMap { t ->
                    t.interfaces.filter { it.first.owner == name && it.second }
                        .map { it.first.signature }
                }

            override fun visitMethod(
                access: Int, name: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?
            ): MethodVisitor {
                visited?.run {
                    methodsByName.add("$className\u0000$name")
                    methodsByDesc.add("$className\u0000$name\u0000$descriptor")
                }
                return super.visitMethod(
                    adjust(access, ElementType.METHOD) { t, d -> t.methodChanges(className, name, descriptor, d) },
                    name, descriptor, signature, exceptions
                )
            }

            override fun visitField(
                access: Int, name: String, descriptor: String,
                signature: String?, value: Any?
            ): FieldVisitor {
                visited?.run {
                    fieldsByName.add("$className\u0000$name")
                    fieldsByDesc.add("$className\u0000$name\u0000$descriptor")
                }
                return super.visitField(
                    adjust(access, ElementType.FIELD) { t, d -> t.fieldChanges(className, name, descriptor, d) },
                    name, descriptor, signature, value
                )
            }

            private fun adjust(
                access: Int,
                type: ElementType,
                changes: (ClassTweaker, Boolean) -> List<AccessElement>
            ): Int {
                val flags = AccessFlag.of(type, access).toMutableSet()
                for (tweaker in tweakers) {
                    for (element in changes(tweaker, false)) element.apply(flags)
                }
                // dependency tweakers contribute only their transitive-*
                // directives to the shared artifact (TransitiveOnlyFilter)
                for (tweaker in depTweakers) {
                    for (element in changes(tweaker, true)) element.apply(flags)
                }
                return AccessFlag.toInt(flags)
            }

        }, 0)
        return writer.toByteArray()
    }

}
