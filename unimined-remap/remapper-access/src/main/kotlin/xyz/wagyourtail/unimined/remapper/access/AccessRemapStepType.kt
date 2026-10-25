package xyz.wagyourtail.unimined.remapper.access

import kotlin.reflect.KClass
import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.MappingVisitor
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.AccessRemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * `remap-access` implementation for engines that copy resources verbatim.
 * It rewrites embedded Access Widener, Forge Access Transformer, and
 * ClassTweaker files using the portable [MappingTree] view.
 */
class AccessRemapStepType : RemapStepType<AccessRemapStepSpec> {

    override val name = "access"
    override val stepName = "remap-access"
    override val specType: KClass<AccessRemapStepSpec> = AccessRemapStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.JAR, RemapSurface.DECOMPILE)
    override val needsMappingTree = true

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: AccessRemapStepSpec, ctx: StepContext) = ArtifactStep { input, c ->
        val tree = c.mappings?.view<MappingTree>()
            ?: throw GradleException("remap-access requires mapping data exposing a MappingTree view")
        val remapper = PortableRemapper(tree, c.spec.sourceNamespace, c.spec.targetNamespace)
        val output = Files.createTempDirectory(c.workDir, "access").resolve(input.fileName.toString())
        val formats = spec.formats.map { it.lowercase() }.toSet()
        val unknown = formats - setOf("aw", "at", "ct")
        if (unknown.isNotEmpty()) {
            throw GradleException("remap-access: unknown format(s): ${unknown.joinToString()}")
        }
        rewriteJar(input, output, remapper, c.spec.targetNamespace, formats, spec.paths)
        output
    }

    private enum class Kind { AW, AT, CT }

    private fun rewriteJar(
        input: Path,
        output: Path,
        remapper: PortableRemapper,
        targetNamespace: String,
        formats: Set<String>,
        explicitPaths: Set<String>
    ) {
        JarFile(input.toFile()).use { jar ->
            JarOutputStream(Files.newOutputStream(output)).use { out ->
                for (entry in jar.entries()) {
                    val next = JarEntry(entry.name)
                    next.time = entry.time
                    out.putNextEntry(next)
                    if (!entry.isDirectory) {
                        val bytes = jar.getInputStream(entry).readBytes()
                        out.write(
                            when (kind(entry.name, bytes, explicitPaths, formats)) {
                                Kind.AW -> remapAw(bytes, remapper, targetNamespace)
                                Kind.AT -> remapAt(bytes, remapper)
                                Kind.CT -> remapCt(bytes, remapper, targetNamespace)
                                null -> bytes
                            }
                        )
                    }
                    out.closeEntry()
                }
            }
        }
    }

    private fun kind(
        path: String,
        bytes: ByteArray,
        explicitPaths: Set<String>,
        formats: Set<String>
    ): Kind? {
        val base = path.substringAfterLast('/')
        val mayContainText = path in explicitPaths ||
            path.endsWith(".accesswidener", true) ||
            base.endsWith(".cfg", true) ||
            base.endsWith(".ct", true) ||
            base.endsWith(".classtweaker", true)
        val firstContentLine by lazy {
            if (!mayContainText) null else bytes.toString(Charsets.UTF_8).lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        }
        val inferred = when {
            path.endsWith(".accesswidener", true) ||
                firstContentLine?.startsWith("accessWidener") == true -> Kind.AW
            firstContentLine?.startsWith("classTweaker") == true ||
                base.endsWith(".ct", true) || base.endsWith(".classtweaker", true) -> Kind.CT
            base.equals("accesstransformer.cfg", true) ||
                base.endsWith("_at.cfg", true) || path in explicitPaths -> Kind.AT
            else -> null
        }
        return inferred?.takeIf { it.format in formats }
    }

    private val Kind.format: String
        get() = when (this) {
            Kind.AW -> "aw"
            Kind.AT -> "at"
            Kind.CT -> "ct"
        }

    private fun remapAw(bytes: ByteArray, remapper: PortableRemapper, targetNs: String): ByteArray =
        bytes.toString(Charsets.UTF_8).lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() || t.startsWith("#") -> line
                t.startsWith("accessWidener") -> {
                    val p = t.split(Regex("\\s+"))
                    if (p.size >= 3) "accessWidener ${p[1]} $targetNs" else line
                }
                else -> remapAwLine(t, remapper)
            }
        }.toByteArray()

    private fun remapAwLine(line: String, remapper: PortableRemapper): String {
        val p = line.split(Regex("\\s+"))
        if (p.size < 3) return line
        val owner = remapper.map(p[2])
        return when (p[1]) {
            "class" -> "${p[0]} class $owner"
            "method" if p.size >= 5 -> {
                val name = if (p[3] == "*" || p[3] == "all" || p[3].startsWith("<")) p[3]
                    else remapper.mapMethod(p[2], p[3], p[4])
                "${p[0]} method $owner $name ${remapper.mapDesc(p[4])}"
            }
            "field" if p.size >= 5 -> {
                val name = if (p[3] == "*" || p[3] == "all") p[3]
                    else remapper.mapField(p[2], p[3], p[4])
                "${p[0]} field $owner $name ${remapper.mapDesc(p[4])}"
            }
            else -> line
        }
    }

    private fun remapAt(bytes: ByteArray, remapper: PortableRemapper): ByteArray =
        bytes.toString(Charsets.UTF_8).lines().joinToString("\n") { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) line else remapAtLine(t, remapper)
        }.toByteArray()

    private fun remapAtLine(line: String, remapper: PortableRemapper): String {
        val comment = line.substringAfter('#', "")
        val suffix = if (comment.isNotEmpty()) " #$comment" else ""
        val body = line.substringBefore('#').trim()
        val p = body.split(Regex("\\s+")).filter(String::isNotEmpty)
        if (p.size < 2) return line
        val srcOwner = p[1].replace('.', '/')
        val owner = remapper.map(srcOwner)
        if (p.size == 2) return "${p[0]} $owner$suffix"
        val member = p[2]
        val paren = member.indexOf('(')
        return if (paren >= 0) {
            val name = member.substring(0, paren)
            val desc = member.substring(paren)
            val mapped = if (name.startsWith("<")) name else remapper.mapMethod(srcOwner, name, desc)
            "${p[0]} $owner $mapped${remapper.mapDesc(desc)}$suffix"
        } else {
            val desc = p.getOrNull(3)
            val mapped = remapper.mapField(srcOwner, member, desc)
            "${p[0]} $owner $mapped" + (desc?.let { " ${remapper.mapDesc(it)}" } ?: "") + suffix
        }
    }

    private fun remapCt(bytes: ByteArray, remapper: PortableRemapper, targetNs: String): ByteArray =
        bytes.toString(Charsets.UTF_8).lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() || t.startsWith("#") -> line
                t.startsWith("classTweaker") -> {
                    val p = t.split(Regex("\\s+"))
                    if (p.size >= 3) "classTweaker\t${p[1]}\t$targetNs" else line
                }
                else -> remapCtLine(t, remapper)
            }
        }.toByteArray()

    private fun remapCtLine(line: String, remapper: PortableRemapper): String {
        val p = line.split(Regex("\\s+"))
        if (p.size < 3) return line
        return when {
            p[1] == "class" -> "${p[0]} class ${remapper.map(p[2])}"
            p[1] == "field" && p.size >= 5 ->
                "${p[0]} field ${remapper.map(p[2])} ${remapper.mapField(p[2], p[3], p[4])} ${remapper.mapDesc(p[4])}"
            p[1] == "method" && p.size >= 5 ->
                "${p[0]} method ${remapper.map(p[2])} ${remapper.mapMethod(p[2], p[3], p[4])} ${remapper.mapDesc(p[4])}"
            p[0].endsWith("inject-interface") && p.size == 3 ->
                "${p[0]} ${remapper.map(p[1])} ${remapper.mapSignature(p[2])}"
            p[0].endsWith("extend-enum") && p.size == 3 ->
                "${p[0]} ${remapper.map(p[1])} ${p[2]}"
            else -> line
        }
    }

    private class PortableRemapper(tree: MappingTree, source: String, target: String) {
        private data class Member(val owner: String, val name: String, val desc: String?)

        private val classes = HashMap<String, String>()
        private val fields = HashMap<Member, String>()
        private val methods = HashMap<Member, String>()
        private val packages = HashMap<String, String>()

        init {
            tree.accept(source, target, object : MappingVisitor {
                override fun visitClass(srcName: String, dstName: String) { classes[srcName] = dstName }
                override fun visitField(srcClass: String, srcName: String, srcDesc: String?, dstName: String) {
                    fields[Member(srcClass, srcName, srcDesc)] = dstName
                }
                override fun visitMethod(srcClass: String, srcName: String, srcDesc: String?, dstName: String) {
                    methods[Member(srcClass, srcName, srcDesc)] = dstName
                }
                override fun visitPackage(srcName: String, dstName: String) { packages[srcName] = dstName }
            })
        }

        fun map(name: String): String = classes[name] ?: packages.entries
            .filter { name.startsWith("${it.key}/") }
            .maxByOrNull { it.key.length }
            ?.let { it.value + name.substring(it.key.length) } ?: name

        fun mapField(owner: String, name: String, desc: String?): String =
            fields[Member(owner, name, desc)]
                ?: fields.entries.firstOrNull { it.key.owner == owner && it.key.name == name }?.value
                ?: name

        fun mapMethod(owner: String, name: String, desc: String?): String =
            methods[Member(owner, name, desc)]
                ?: methods.entries.firstOrNull { it.key.owner == owner && it.key.name == name }?.value
                ?: name

        fun mapDesc(desc: String): String = Regex("L([^;]+);").replace(desc) {
            "L${map(it.groupValues[1])};"
        }

        fun mapSignature(signature: String): String {
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
    }
}
