package xyz.wagyourtail.unimined.internal.mapping.at

import net.fabricmc.accesswidener.AccessWidenerReader
import net.fabricmc.accesswidener.AccessWidenerWriter
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import net.neoforged.accesstransformer.*
import net.neoforged.accesstransformer.AccessTransformer.Modifier
import net.neoforged.accesstransformer.Target
import net.neoforged.accesstransformer.parser.AccessTransformerList
import org.apache.commons.io.output.NullOutputStream
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logger
import org.gradle.api.logging.configuration.ShowStacktrace
import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.*

object AccessTransformerApplier {

    class AtRemapper(val logger: Logger, val remapToLegacy: Boolean = false, val atPaths: List<String> = emptyList(), val isLegacy: Boolean = false): OutputConsumerPath.ResourceRemapper {

        override fun canTransform(remapper: TinyRemapper, relativePath: Path): Boolean {
            return relativePath.name == "accesstransformer.cfg" ||
                    relativePath.name.endsWith("_at.cfg") || atPaths.contains(relativePath.name)
        }

        override fun transform(
            destinationDirectory: Path,
            relativePath: Path,
            input: InputStream,
            remapper: TinyRemapper
        ) {
            val output = destinationDirectory.resolve(relativePath)
            output.parent.createDirectories()
            input.bufferedReader().use { reader ->
                if (isLegacy) {
                    TransformFromLegacyTransformer(reader)
                } else {
                    reader
                }.use { fromLegacy ->
                    RemapModernTransformer(fromLegacy.buffered(), remapper, logger).use { remapped ->
                        Files.newBufferedWriter(
                            output,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING
                        ).use {
                            remapped.copyTo(
                                if (remapToLegacy) {
                                    TransformToLegacyTransformer(it)
                                } else {
                                    it
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    fun toModern(path: Path) {
        val writer = StringWriter()
        path.bufferedReader().use {
            TransformFromLegacyTransformer(it).use { fromLegacy ->
                fromLegacy.copyTo(writer)
            }
        }
        path.writeText(writer.toString(), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)
    }

    fun toModern(reader: Reader): Reader {
        return TransformFromLegacyTransformer(reader.buffered())
    }

    private val legacyMethod = Regex(
        "^(\\w+(?:[\\-+]f)?)\\s+([\\w.$]+)\\.([\\w*<>]+)(\\(.+?)\\s*?(#.+?)?\$",
        RegexOption.MULTILINE
    )
    private val legacyField = Regex(
        "^(\\w+(?:[\\-+]f)?)\\s+([\\w.$]+)\\.([\\w*<>]+)\\s*?(#.+?)?\$",
        RegexOption.MULTILINE
    )

    private val modernClass = Regex("^(\\w+(?:[\\-+]f)?)\\s+([\\w.$]+)\\s*?(#.+?)?\$", RegexOption.MULTILINE)
    private val modernMethod = Regex(
        "^(\\w+(?:[\\-+]f)?)\\s+([\\w.\$]+)\\s+([\\w*<>]+)(\\(.+?)\\s*?(#.+?)?\$",
        RegexOption.MULTILINE
    )
    private val modernField = Regex(
        "^(\\w+(?:[\\-+]f)?)\\s+([\\w.\$]+)\\s+([\\w*<>]+)\\s*?(#.+?)?\$",
        RegexOption.MULTILINE
    )

    private class TransformFromLegacyTransformer(val reader: BufferedReader) : Reader() {
        var line: String? = null
        var closed = false

        @Synchronized
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (closed) {
                throw IOException("Reader is closed")
            }
            if (len == 0) {
                return 0
            }
            if (line != null) {
                val read = line!!.length.coerceAtMost(len)
                line!!.toCharArray(cbuf, off, 0, read)
                if (read == line!!.length) {
                    line = null
                    return read
                }
                line = line!!.substring(read)
                return read
            }
            line = reader.readLine()
            if (line == null) {
                return -1
            }
            line = transformFromLegacyTransformer(line!!.trimStart()) + "\n"
            return read(cbuf, off, len)
        }

        override fun ready(): Boolean {
            return line != null || reader.ready()
        }

        @Synchronized
        override fun close() {
            if (!closed) {
                reader.close()
                closed = true
            }
        }
    }

    private fun transformFromLegacyTransformer(line: String): String {
        val methodMatch = legacyMethod.matchEntire(line)
        if (methodMatch != null) {
            val (access, owner, name, desc, comment) = methodMatch.destructured
            if (name == "*") {
                if (desc == "()") {
                    return "$access $owner $name${desc} $comment"
                }
            }
            return if (desc.endsWith(")")) {
                if (!name.contains("<")) {
                    throw IllegalStateException("Missing return type in access transformer: $line")
                }
                "$access $owner $name${desc}V $comment"
            } else {
                "$access $owner $name${desc} $comment"
            }
        }
        val fieldMatch = legacyField.matchEntire(line)
        if (fieldMatch != null) {
            val (access, owner, name, comment) = fieldMatch.destructured
            return "$access $owner $name $comment"
        }
        return line
    }

    private class TransformToLegacyTransformer(val writer: BufferedWriter) : Writer(writer) {
        var lineBuffer: String? = null
        var closed = false

        @Synchronized
        override fun close() {
            if (closed) {
                return
            }
            closed = true
            if (lineBuffer != null) {
                writer.write(TransformToLegacyTransformer(lineBuffer!!))
                lineBuffer = null
            }
            writer.close()
        }

        override fun flush() {
            writer.flush()
        }

        @Synchronized
        override fun write(cbuf: CharArray, off: Int, len: Int) {
            if (closed) {
                throw IOException("Writer is closed")
            }
            if (len == 0) {
                return
            }
            if (lineBuffer == null) {
                lineBuffer = String(cbuf, off, len)
            } else {
                lineBuffer += String(cbuf, off, len)
            }
            if (lineBuffer!!.contains('\n')) {
                val lines = lineBuffer!!.split('\n')
                lineBuffer = lines.last()
                if (lineBuffer!!.isEmpty()) {
                    lineBuffer = null
                }
                lines.dropLast(1).forEach {
                    writer.write(TransformToLegacyTransformer(it))
                    writer.write("\n")
                }
            }
        }
    }

    private fun TransformToLegacyTransformer(line: String): String {
        val methodMatch = modernMethod.matchEntire(line)
        if (methodMatch != null) {
            val (access, owner, name, desc, comment) = methodMatch.destructured
            return "$access $owner.$name${desc} $comment"
        }
        val fieldMatch = modernField.matchEntire(line)
        if (fieldMatch != null) {
            val (access, owner, name, comment) = fieldMatch.destructured
            return "$access $owner.$name $comment"
        }
        return line
    }

    private class RemapModernTransformer(val reader: BufferedReader, val remapper: TinyRemapper, val logger: Logger) : Reader() {
        var line: String? = null
        var closed = false

        @Synchronized
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (closed) {
                throw IOException("Reader is closed")
            }
            if (len == 0) {
                return 0
            }
            if (line != null) {
                val read = line!!.length.coerceAtMost(len)
                line!!.toCharArray(cbuf, off, 0, read)
                if (read == line!!.length) {
                    line = null
                    return read
                }
                line = line!!.substring(read)
                return read
            }
            line = reader.readLine()
            if (line == null) {
                return -1
            }
            line = RemapModernTransformer(line!!, remapper, logger) + "\n"
            return read(cbuf, off, len)
        }

        override fun ready(): Boolean {
            return line != null || reader.ready()
        }

        @Synchronized
        override fun close() {
            if (!closed) {
                reader.close()
                closed = true
            }
        }
    }

    private fun RemapModernTransformer(line: String, tremapper: TinyRemapper, logger: Logger): String {
        try {
            val remapper = tremapper.environment
            val classMatch = modernClass.matchEntire(line)
            if (classMatch != null) {
                val (access, owner, comment) = classMatch.destructured
                val remappedOwner = remapper.remapper.map(owner.replace(".", "/")).replace("/", ".")
                return "$access $remappedOwner $comment"
            }
            val methodMatch = modernMethod.matchEntire(line)
            if (methodMatch != null) {
                val (access, owner, name, desc, comment) = methodMatch.destructured
                val remappedOwner = remapper.remapper.map(owner.replace(".", "/")).replace("/", ".")
                if (name == "*") {
                    if (desc == "()") {
                        return "$access $remappedOwner $name$desc $comment"
                    }
                }
                var fixedDesc = desc
                if (name == "<init>" || name == "<clinit>") {
                    if (fixedDesc.endsWith(")")) {
                        fixedDesc += "V"
                    }
                }
                val remappedName = remapper.remapper.mapMethodName(owner.replace(".", "/"), name, fixedDesc)
                val remappedDesc = remapper.remapper.mapMethodDesc(fixedDesc)
                return "$access $remappedOwner $remappedName$remappedDesc $comment"
            }
            val fieldMatch = modernField.matchEntire(line)
            if (fieldMatch != null) {
                val (access, owner, name, comment) = fieldMatch.destructured
                val remappedOwner = remapper.remapper.map(owner.replace(".", "/")).replace("/", ".")
                val remappedName = remapper.getClass(owner.replace(".", "/"))?.fields?.firstOrNull { it.name == name }?.newName ?: name
                return "$access $remappedOwner $remappedName $comment"
            }
            logger.info("Failed to match: $line")
            return line
        } catch (e: Exception) {
            e.printStackTrace()
            throw IllegalStateException("Failed to remap line: $line", e)
        }
    }


    fun shouldShowVerboseStdout(project: Project): Boolean {
        // if running with INFO or DEBUG logging
        return project.gradle.startParameter.logLevel < LogLevel.LIFECYCLE
    }

    fun shouldShowVerboseStderr(project: Project): Boolean {
        // if stdout is shown or stacktraces are visible so that errors printed to stderr show up
        return shouldShowVerboseStdout(project) || project.gradle.startParameter.showStacktrace != ShowStacktrace.INTERNAL_EXCEPTIONS
    }

    fun aw2at(aw: Path, output: Path, legacy: Boolean = false): Path {
        val outWriter = output.bufferedWriter(
            StandardCharsets.UTF_8,
            1024,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
        val writer = if (legacy) TransformToLegacyTransformer(outWriter) else outWriter
        AccessTransformerWriter(writer.buffered()).use {
            AccessWidenerReader(it).read(aw.bufferedReader())
        }
        return output
    }

    fun at2aw(at: Path, output: Path, namespace: String, mappings: MappingTreeView, logger: Logger): Path {
        val inReader = at.bufferedReader(
            StandardCharsets.UTF_8,
            1024,
            StandardOpenOption.READ
        )

        val reader = TransformFromLegacyTransformer(inReader)
        val temp = at.resolveSibling(at.name + ".temp")
        val tempWriter = temp.bufferedWriter(
            StandardCharsets.UTF_8,
            1024,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
        reader.copyTo(tempWriter)

        val masterList = AccessTransformerList()
        masterList.loadFromPath(temp, at.name)
        temp.deleteIfExists()
        val awWriter = AccessWidenerWriter()
        awWriter.visitHeader(namespace)

        val targetAccess = AccessTransformer::class.java.getField("targetAccess")
        val targetFinalState = AccessTransformer::class.java.getField("targetFinalState")
        val targetMember = AccessTransformer::class.java.getField("memberTarget")

        val namespaceId = mappings.getNamespaceId(namespace)

        masterList.accessTransformers.forEach { (_, transformers) ->
            transformers.forEach { it ->
                val tAccess = targetAccess.get(it) as Modifier
                val tFinal = targetFinalState.get(it) as AccessTransformer.FinalState
                val member = targetMember.get(it) as Target<*>

                when (member.type) {
                    TargetType.FIELD -> {
                        val fieldName = member.targetName()

                        val fieldDesc = mappings.getClass(member.className, namespaceId).fields.first { fieldView ->
                            fieldView.getName(namespaceId) == fieldName
                        }.getDesc(namespaceId)

                        when (tAccess) {
                            Modifier.PUBLIC -> {
                                awWriter.visitField(
                                    member.className,
                                    fieldName,
                                    fieldDesc,
                                    AccessWidenerReader.AccessType.ACCESSIBLE,
                                    false
                                )
                            }

                            Modifier.PROTECTED -> {
                                logger.warn("Tried to make ${member.className}.${fieldName} $fieldDesc 'protected' but AW don't support doing this\nmaking the field public instead.")
                                awWriter.visitField(
                                    member.className,
                                    fieldName,
                                    fieldDesc,
                                    AccessWidenerReader.AccessType.ACCESSIBLE,
                                    false
                                )
                            }

                            Modifier.DEFAULT -> {
                                logger.warn("Tried to make ${member.className}.${fieldName} $fieldDesc 'default' but AW don't support doing this.")
                            }

                            Modifier.PRIVATE -> {
                                logger.warn("Tried to make ${member.className}.${fieldName} $fieldDesc 'default' but AW don't support doing this.")
                            }
                        }

                        when (tFinal) {
                            AccessTransformer.FinalState.LEAVE -> {}
                            AccessTransformer.FinalState.MAKEFINAL -> {
                                logger.warn("Tried to make ${member.className}.${fieldName} $fieldDesc 'final' but AW don't support doing this.")
                            }

                            AccessTransformer.FinalState.REMOVEFINAL -> {
                                awWriter.visitField(
                                    member.className,
                                    fieldName,
                                    fieldDesc,
                                    AccessWidenerReader.AccessType.MUTABLE,
                                    false
                                )
                            }

                            AccessTransformer.FinalState.CONFLICT -> {
                                logger.warn("FINAL-STATE CONFLICT FOR ${member.className}.${fieldName} $fieldDesc!")
                            }
                        }
                    }

                    TargetType.METHOD -> {
                        val parts = (member as MethodTarget).targetName().split("(").toMutableList();
                        parts[1] = "(" + parts[1]
                        when (tAccess) {
                            Modifier.PUBLIC -> {
                                awWriter.visitMethod(
                                    member.className,
                                    parts[0],
                                    parts[1],
                                    AccessWidenerReader.AccessType.ACCESSIBLE,
                                    false
                                )
                            }

                            Modifier.PROTECTED -> {
                                logger.warn("Tried to make ${member.className}.${parts[0]}${parts[1]} 'protected' but AW don't support doing this\nmaking the function public instead.")
                                awWriter.visitMethod(
                                    member.className,
                                    parts[0],
                                    parts[1],
                                    AccessWidenerReader.AccessType.ACCESSIBLE,
                                    false
                                )
                            }

                            Modifier.DEFAULT -> {
                                logger.warn("Tried to make ${member.className}.${parts[0]}${parts[1]} 'default' but AW don't support doing this.")
                            }

                            Modifier.PRIVATE -> {
                                logger.warn("Tried to make ${member.className}.${parts[0]}${parts[1]} 'private' but AW don't support doing this.")
                            }
                        }

                        when (tFinal) {
                            AccessTransformer.FinalState.LEAVE -> {}
                            AccessTransformer.FinalState.MAKEFINAL -> {
                                logger.warn("Tried to make ${member.className}.${parts[0]}${parts[1]} 'final' but AW don't support doing this.")
                            }

                            AccessTransformer.FinalState.REMOVEFINAL -> awWriter.visitMethod(
                                member.className,
                                parts[0],
                                parts[1],
                                AccessWidenerReader.AccessType.EXTENDABLE,
                                false
                            )

                            AccessTransformer.FinalState.CONFLICT -> {
                                logger.warn("FINAL-STATE CONFLICT FOR ${member.className}.${parts[0]}${parts[1]}!")
                            }
                        }
                    }

                    else -> {
                        val className = if (member is InnerClassTarget) {
                            member.className + "$" + member.targetName()
                        } else member.className

                        if (member is ClassTarget || member is InnerClassTarget) {
                            when (tAccess) {
                                Modifier.PUBLIC -> awWriter.visitClass(
                                    className,
                                    AccessWidenerReader.AccessType.ACCESSIBLE,
                                    false
                                )

                                Modifier.PROTECTED -> {
                                    logger.warn("Tried to make $className 'protected' but AW don't support doing this\nmaking the class public instead.")
                                    awWriter.visitClass(
                                        className,
                                        AccessWidenerReader.AccessType.ACCESSIBLE,
                                        false
                                    )
                                }

                                Modifier.DEFAULT -> {
                                    logger.warn("Tried to make $className 'default' but AW don't support doing this.")
                                }

                                Modifier.PRIVATE -> {
                                    logger.warn("Tried to make $className 'private' but AW don't support doing this.")
                                }
                            }

                            when (tFinal) {
                                AccessTransformer.FinalState.LEAVE -> {}
                                AccessTransformer.FinalState.MAKEFINAL -> {
                                    logger.warn("Tried to make $className 'final' but AW don't support doing this.")
                                }

                                AccessTransformer.FinalState.REMOVEFINAL -> awWriter.visitClass(
                                    className,
                                    AccessWidenerReader.AccessType.EXTENDABLE,
                                    false
                                )

                                AccessTransformer.FinalState.CONFLICT -> {
                                    logger.warn("FINAL-STATE CONFLICT FOR $className!")
                                }
                            }
                        } else {
                            when (member.targetName()) {
                                "*METHOD*" -> {
                                    mappings.getClass(member.className, namespaceId).methods.forEach { methodView ->
                                        val methodName = methodView.getName(namespaceId)
                                        val methodDesc = methodView.getDesc(namespaceId)

                                        when (tAccess) {
                                            Modifier.PUBLIC -> {
                                                awWriter.visitMethod(
                                                    member.className,
                                                    methodName,
                                                    methodDesc,
                                                    AccessWidenerReader.AccessType.ACCESSIBLE,
                                                    false
                                                )
                                            }

                                            Modifier.PROTECTED -> {
                                                logger.warn("Tried to make ${member.className}.${methodName}${methodDesc} 'protected' but AW don't support doing this\nmaking the function public instead.")
                                                awWriter.visitMethod(
                                                    member.className,
                                                    methodName,
                                                    methodDesc,
                                                    AccessWidenerReader.AccessType.ACCESSIBLE,
                                                    false
                                                )
                                            }

                                            Modifier.DEFAULT -> {
                                                logger.warn("Tried to make ${member.className}.${methodName}${methodDesc} 'default' but AW don't support doing this.")
                                            }

                                            Modifier.PRIVATE -> {
                                                logger.warn("Tried to make ${member.className}.${methodName}${methodDesc} 'private' but AW don't support doing this.")
                                            }
                                        }

                                        when (tFinal) {
                                            AccessTransformer.FinalState.LEAVE -> {}
                                            AccessTransformer.FinalState.MAKEFINAL -> {
                                                logger.warn("Tried to make ${member.className}.${methodName}${methodDesc} 'final' but AW don't support doing this.")
                                            }

                                            AccessTransformer.FinalState.REMOVEFINAL -> awWriter.visitMethod(
                                                member.className,
                                                methodName,
                                                methodDesc,
                                                AccessWidenerReader.AccessType.EXTENDABLE,
                                                false
                                            )

                                            AccessTransformer.FinalState.CONFLICT -> {
                                                logger.warn("FINAL-STATE CONFLICT FOR ${member.className}.${methodName}${methodDesc}!")
                                            }
                                        }
                                    }
                                }

                                else -> {
                                    mappings.getClass(member.className, namespaceId).fields.forEach { fieldView ->
                                        val fieldName = fieldView.getName(namespaceId)
                                        val fieldDesc = fieldView.getDesc(namespaceId)

                                        when (tAccess) {
                                            Modifier.PUBLIC -> {
                                                awWriter.visitField(
                                                    member.className,
                                                    fieldName,
                                                    fieldDesc,
                                                    AccessWidenerReader.AccessType.ACCESSIBLE,
                                                    false
                                                )
                                            }

                                            Modifier.PROTECTED -> {
                                                logger.warn("Tried to make ${member.className}.${fieldName} $fieldDesc 'protected' but AW don't support doing this\nmaking the field public instead.")
                                                awWriter.visitField(
                                                    member.className,
                                                    fieldName,
                                                    fieldDesc,
                                                    AccessWidenerReader.AccessType.ACCESSIBLE,
                                                    false
                                                )
                                            }

                                            Modifier.DEFAULT -> {
                                                logger.warn("Tried to make ${member.className}.${fieldName} $fieldDesc 'default' but AW don't support doing this.")
                                            }

                                            Modifier.PRIVATE -> {
                                                logger.warn("Tried to make ${member.className}.${fieldName} $fieldDesc 'default' but AW don't support doing this.")
                                            }
                                        }

                                        when (tFinal) {
                                            AccessTransformer.FinalState.LEAVE -> {}
                                            AccessTransformer.FinalState.MAKEFINAL -> {
                                                logger.warn("Tried to make ${member.className}.${fieldName} $fieldDesc 'final' but AW don't support doing this.")
                                            }

                                            AccessTransformer.FinalState.REMOVEFINAL -> {
                                                awWriter.visitField(
                                                    member.className,
                                                    fieldName,
                                                    fieldDesc,
                                                    AccessWidenerReader.AccessType.MUTABLE,
                                                    false
                                                )
                                            }

                                            AccessTransformer.FinalState.CONFLICT -> {
                                                logger.warn("FINAL-STATE CONFLICT FOR ${member.className}.${fieldName} $fieldDesc!")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        output.bufferedWriter(
            StandardCharsets.UTF_8,
            1024,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        ).write(awWriter.writeString())

        return output
    }
}