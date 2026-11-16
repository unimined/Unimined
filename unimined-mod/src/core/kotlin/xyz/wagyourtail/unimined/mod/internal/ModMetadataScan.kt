package xyz.wagyourtail.unimined.mod.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.streams.asSequence

/**
 * Class-file analysis backing metadata auto-fill: which interfaces a
 * mixin injects onto which targets, and which compiled classes are
 * gametest entrypoints. Reads class bytes directly — no classloading —
 * so it works on any toolchain output.
 */
internal object ModMetadataScan {

    private const val MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;"

    /** What one class file declares for interface-injection purposes. */
    data class ClassInfo(
        val superName: String?,
        val interfaces: List<String>,
        /** annotation descriptors (`Lfoo/Bar;`) seen on any method —
         *  for method-level markers like fabric's 1.20-era `@GameTest`. */
        val methodAnnotations: Set<String> = emptySet()
    )

    /** A parsed `*.mixins.json`: root package + member class names. */
    data class MixinConfigClasses(
        val packageName: String,
        /** fully-qualified class names across mixins/client/server. */
        val classes: List<String>
    )

    /**
     * Parse a mixin config document (`package` + `mixins`/`client`/
     * `server` string arrays) into fully-qualified class names.
     * Returns `null` on unparseable input — callers skip silently since
     * the loader itself will surface malformed configs.
     */
    fun parseMixinConfig(text: String): MixinConfigClasses? = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text)
            .jsonObject
        val pkg = root["package"]?.jsonPrimitive?.content ?: return null
        val names = listOf("mixins", "client", "server")
            .flatMap { key ->
                (root[key] as? JsonArray)?.mapNotNull {
                    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                }.orEmpty()
            }
        MixinConfigClasses(pkg, names.map { "$pkg.$it" })
    }.getOrNull()

    /**
     * Discover `*.mixins.json` files under [resourceDirs]; returns
     * resource-root-relative paths paired with their parsed class lists.
     * [exclude] drops paths already covered by generated configs.
     */
    fun discoverMixinConfigs(
        resourceDirs: Collection<File>,
        exclude: Set<String> = emptySet()
    ): List<Pair<String, MixinConfigClasses>> {
        val out = mutableListOf<Pair<String, MixinConfigClasses>>()
        for (dir in resourceDirs) {
            if (!dir.isDirectory) continue
            Files.walk(dir.toPath()).use { stream ->
                stream.asSequence()
                    .filter { it.isRegularFile() && it.name.endsWith(".mixins.json") }
                    .map { it.relativeTo(dir.toPath()).toString().replace('\\', '/') }
                    .filter { it !in exclude }
                    .forEach { rel ->
                        val text = runCatching { Files.readString(dir.toPath().resolve(rel)) }
                            .getOrNull() ?: return@forEach
                        parseMixinConfig(text)?.let { out += rel to it }
                    }
            }
        }
        return out
    }

    /**
     * Scan every `.class` file under [classesDirs] once, returning
     * internal name → [ClassInfo]. Missing/unreadable entries are
     * skipped — the scan is best-effort metadata, not validation.
     */
    fun scanClasses(classesDirs: Collection<File>): Map<String, ClassInfo> {
        val out = LinkedHashMap<String, ClassInfo>()
        for (dir in classesDirs) {
            if (!dir.isDirectory) continue
            Files.walk(dir.toPath()).use { stream ->
                stream.asSequence()
                    .filter { it.isRegularFile() && it.extension == "class" }
                    .forEach { file ->
                        val rel = file.fileName.toString()
                            .removeSuffix(".class").replace('\\', '/')
                        // path relative to the classes root gives the
                        // internal name; Files.walk order isn't
                        // guaranteed so derive it properly below
                        val root = dir.toPath()
                        val internal = file.toAbsolutePath()
                            .relativeTo(root.toAbsolutePath())
                            .toString().replace('\\', '/')
                            .removeSuffix(".class")
                        if (internal in out) return@forEach
                        val bytes = runCatching { Files.readAllBytes(file) }
                            .getOrNull() ?: return@forEach
                        readInfo(bytes)?.let { out[internal] = it }
                    }
            }
        }
        return out
    }

    /**
     * Class names (dot form) implementing [ifaceInternal] — direct
     * implementors plus subclasses whose ancestry resolves inside the
     * scanned set (a subclass of a gametest is itself a gametest).
     * When [annotationInternal] is given (internal name, no `L;`),
     * classes carrying it on any method match too — the 1.20-era fabric
     * API marks tests with a method annotation instead of an interface.
     */
    fun gametestClasses(
        classesDirs: Collection<File>,
        ifaceInternal: String,
        annotationInternal: String? = null
    ): List<String> {
        val infos = scanClasses(classesDirs)
        val memo = HashMap<String, Boolean>()
        fun isGame(internal: String): Boolean = memo.getOrPut(internal) {
            val info = infos[internal] ?: return@getOrPut false
            info.interfaces.contains(ifaceInternal) ||
                    (info.superName?.let { isGame(it) } == true)
        }
        val annotationDescriptor = annotationInternal?.let { "L$it;" }
        return infos.keys.filter { internal ->
            isGame(internal) ||
                (annotationDescriptor != null &&
                    annotationDescriptor in (infos[internal]?.methodAnnotations
                        ?: emptySet()))
        }.map { it.replace('/', '.') }
            .sorted()
    }

    /**
     * Interface injections derivable from mixin classes:
     * for each mixin class file found under [classesDirs], every
     * `@Mixin` target gains every interface the mixin implements.
     * Returns target internal name → interface internal names,
     * minus pairs already declared in [existingRules] (owner +
     * signature pairs scraped from the project's own access files).
     */
    fun interfaceInjections(
        mixinClassNames: List<String>,
        classesDirs: Collection<File>,
        existingRules: Set<Pair<String, String>>
    ): Map<String, List<String>> {
        if (mixinClassNames.isEmpty() || classesDirs.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        for (fqName in mixinClassNames) {
            val rel = fqName.replace('.', '/') + ".class"
            val bytes = classesDirs.firstNotNullOfOrNull { dir ->
                val f = dir.toPath().resolve(rel)
                if (Files.isRegularFile(f)) runCatching { Files.readAllBytes(f) }.getOrNull()
                else null
            } ?: continue
            val info = readMixinInfo(bytes) ?: continue
            for (target in info.targets) {
                for (iface in info.interfaces) {
                    if (iface == target) continue
                    val sig = normalizeSignature(iface)
                    if ((target to sig) in existingRules) continue
                    out.getOrPut(target) { mutableListOf() }
                        .add(iface)
                }
            }
        }
        // dedupe interface lists per target, drop targets left empty
        return out.mapValues { (_, ifaces) -> ifaces.distinct() }
            .filterValues { it.isNotEmpty() }
    }

    /** `inject-interface`/`transitive-inject-interface` rules already
     *  declared in access files — (owner, normalized signature) pairs
     *  so generation never duplicates a user-authored directive. */
    fun existingInterfaceRules(files: Collection<File>): Set<Pair<String, String>> {
        val out = HashSet<Pair<String, String>>()
        val re = Regex(
            """^\s*(?:transitive-)?inject-interface\s+(\S+)\s+(\S+)\s*$""",
            RegexOption.MULTILINE
        )
        for (file in files) {
            if (!file.isFile) continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            for (m in re.findAll(text)) {
                out += m.groupValues[1] to normalizeSignature(m.groupValues[2])
            }
        }
        return out
    }

    /** `Lfoo/Bar<TT;>;` / `foo/Bar<TT;>` / `Lfoo/Bar;` → `foo/Bar`. */
    internal fun normalizeSignature(sig: String): String =
        sig.removePrefix("L").substringBefore('<')
            .removeSuffix(";")

    private data class MixinInfo(
        val targets: List<String>,
        val interfaces: List<String>
    )

    private fun readInfo(bytes: ByteArray): ClassInfo? {
        val visitor = InfoVisitor()
        runCatching { ClassReader(bytes).accept(visitor, ClassReader.SKIP_CODE or
                ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) }
            .getOrNull() ?: return null
        return visitor.info
    }

    private fun readMixinInfo(bytes: ByteArray): MixinInfo? {
        val visitor = MixinVisitor()
        runCatching { ClassReader(bytes).accept(visitor, ClassReader.SKIP_CODE or
                ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) }
            .getOrNull() ?: return null
        if (!visitor.hasMixin) return null
        return MixinInfo(visitor.targets, visitor.interfaces)
    }

    private open class InfoVisitor : ClassVisitor(Opcodes.ASM9) {
        private var visited = false
        private var superName: String? = null
        private var interfaces: List<String> = emptyList()
        private val methodAnnotations = mutableSetOf<String>()

        val info: ClassInfo?
            get() = if (visited) ClassInfo(
                superName, interfaces, methodAnnotations
            ) else null

        override fun visit(
            version: Int, access: Int, name: String,
            signature: String?, superName: String?, interfaces: Array<out String>?
        ) {
            visited = true
            this.superName = superName
            this.interfaces = interfaces?.toList().orEmpty()
        }

        override fun visitMethod(
            access: Int, name: String, descriptor: String,
            signature: String?, exceptions: Array<out String>?
        ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(
                descriptor: String, visible: Boolean
            ): AnnotationVisitor? {
                methodAnnotations += descriptor
                return null
            }
        }
    }

    private class MixinVisitor : ClassVisitor(Opcodes.ASM9) {
        var hasMixin = false
            private set
        val targets = mutableListOf<String>()
        val interfaces = mutableListOf<String>()
        private var superName: String? = null

        override fun visit(
            version: Int, access: Int, name: String,
            signature: String?, superName: String?, interfaces: Array<out String>?
        ) {
            this.superName = superName
            this.interfaces += interfaces?.toList().orEmpty()
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
            if (descriptor != MIXIN_ANNOTATION) return null
            hasMixin = true
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visitArray(name: String?): AnnotationVisitor {
                    val arrayName = name
                    return object : AnnotationVisitor(Opcodes.ASM9) {
                        override fun visit(elementName: String?, value: Any?) {
                            when (arrayName) {
                                "value" -> (value as? Type)
                                    ?.takeIf { it.sort == Type.OBJECT }
                                    ?.let { targets += it.internalName }

                                "targets" -> (value as? String)
                                    ?.let { targets += normalizeTarget(it) }
                            }
                        }
                    }
                }
            }
        }

        /** `targets` strings may be dotted, internal, or `L...;` —
         *  normalize to a bare internal name. */
        private fun normalizeTarget(raw: String): String =
            raw.removePrefix("L").removeSuffix(";")
                .replace('.', '/')
    }

}
