package net.fabricmc.mappingio.format

import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.MappingNsRenamer
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.tree.MappingTree
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.util.*
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.name

class MappingTreeBuilder {
    private var tree = MemoryMappingTree()
    private var globalFMV: (MappingVisitor) -> MappingVisitor = { it }
    private var frozen by FinalizeOnWrite(false)
    private val ns = mutableSetOf<String>()
    private var side by FinalizeOnRead(EnvType.COMBINED)
    private var sourceNs by FinalizeOnRead("official")

    private val onBuild = mutableListOf<OnBuildTask>()

    data class OnBuildTask(val name: String, val dep: Set<String>, val out: Set<String>, val action: () -> Unit) {
        override fun toString(): String {
            return "OnBuildTask(name=$name, dep=$dep, out=$out)"
        }
    }

    private fun checkFrozen() {
        if (frozen) {
            throw IllegalStateException("Cannot modify frozen mapping tree")
        }
    }

    private fun checkInput(input: MappingInputBuilder.MappingInput) {
        if (input.nsFilter.intersect(ns).isNotEmpty() && !input.allowDuplicate) {
            throw IllegalArgumentException("Namespace ${input.nsFilter.intersect(ns)} already exists in the tree")
        }
    }

    fun side(env: EnvType) {
        checkFrozen()
        side = env
    }

    fun sourceNs(ns: String) {
        checkFrozen()
        sourceNs = ns
    }

    fun MappingInputBuilder.MappingInput.wrapInput(name: String, reader: BufferedReader?, action: (BufferedReader?) -> Unit) {
        val tempFile = if (reader != null) {
            // cache the reader
            File.createTempFile("mapping-input", ".tmp").apply {
                deleteOnExit()
                // write reader to file
                bufferedWriter().use { out ->
                    reader.use { it.copyTo(out) }
                }
            }
        } else null
        val task = OnBuildTask(name, (dependsOn + setOf(nsSource) - sourceNs).toSet(), nsFilter) {
            if (tempFile != null) {
                tempFile.bufferedReader().use {
                    try {
                        action(it)
                    } finally {
                        tempFile.delete()
                    }
                }
            } else {
                action(null)
            }
        }
        // determine where to put in after list based on being in the output of a previous
        val remainingDepends = task.dep.toMutableSet()
        for (i in 0 until onBuild.size) {
            remainingDepends -= onBuild[i].dep
            if (remainingDepends.isEmpty()) {
                onBuild.add(i, task)
//                println(onBuild)
                return
            }
            remainingDepends -= onBuild[i].out
        }
        // source wasn't found in prev's dst's
        onBuild.add(task)
//        println(onBuild)
    }

    fun reprocessWithAddedGlobalFMV(newVisitor: (MappingVisitor) -> MappingVisitor) {
        val oldTree = tree
        tree = MemoryMappingTree()
        val oldFMV = globalFMV
        globalFMV = { newVisitor(oldFMV(it)) }
        oldTree.accept(newVisitor(tree as MappingVisitor))
    }

    fun bytecodeJar(file: Path, inputs: MappingInputBuilder) {
        checkFrozen()
        val input = inputs.build("", BetterMappingFormat.OBF_JAR)
        checkInput(input)
        val visitor = MappingNsRenamer(
            MappingSourceNsSwitch(
                input.fmv(
                    MappingDstNsFilter(
                        tree, input.nsFilter
                    ), tree, side
                ),
                input.nsSource,
            ), input.nsMap
        )
        val preDstNs = tree.dstNamespaces ?: emptyList()
        BytecodeToMappings.readFile(file, "official", visitor)
        val postDstNs = tree.dstNamespaces ?: emptyList()
        ns.addAll(postDstNs - preDstNs.toSet())
    }

    fun mappingFile(file: Path, input: MappingInputBuilder) {
        checkFrozen()
        if (file.isZip()) {
            val found = mutableSetOf<Pair<BetterMappingFormat, String>>()
            file.forEachInZip { name, stream ->
                try {
                    val reader = stream.bufferedReader()
                    if (listOf(
                            name.endsWith(".tsrg"),
                            name.endsWith(".rgs"),
                            name.endsWith(".srg"),
                            name.endsWith(".csrg"),
                            name.endsWith(".tiny"),
                            name.endsWith(".csv"),
                            name.endsWith(".mapping"),
                            name.endsWith(".json")
                        ).any { it }
                    ) {
                        val header = detectHeader(reader, name)
                        if (header != null) {
                            found.add(header to name)
                        }
                    }
                } catch (e: Exception) {
                    throw IOException("Error reading header on $name", e)
                }
            }
            found.sortedWith { a, b ->
                if (a.first == b.first) {
                    b.second.compareTo(a.second)
                } else {
                    a.first.ordinal.compareTo(b.first.ordinal)
                }
            }.forEach {
                try {
                    if (it.first == BetterMappingFormat.RETROGUARD) {
                        if (side == EnvType.COMBINED) throw IllegalArgumentException("Cannot use retroguard mappings in combined mode")
                        if (it.second.endsWith("_server.rgs") && side.mcp == 0) {
                            return@forEach
                        } else if (!it.second.endsWith("_server.rgs") && side.mcp == 1) {
                            return@forEach
                        }
                    }
                    if (it.first == BetterMappingFormat.SRG) {
                        val combined = it.second.endsWith("joined.srg")
                        if (side == EnvType.COMBINED && !combined) {
                            throw IllegalArgumentException("Cannot use srg mappings in combined mode as joined.srg is required")
                        }
                        if (!combined) {
                            if (side == EnvType.CLIENT && !it.second.endsWith("client.srg")) {
//                                println("Skipping ${it.second}")
                                return@forEach
                            }
                            if (side == EnvType.SERVER && !it.second.endsWith("server.srg")) {
//                                println("Skipping ${it.second}")
                                return@forEach
                            }
                        }
                    }
                    file.readZipInputStreamFor(it.second) { stream ->
                        mappingReaderIntl(it.second, stream.bufferedReader(), input, it.first)
                    }
                } catch (e: Exception) {
                    throw IOException("Error reading ${file.name}!/${it.second}", e)
                }
            }
        } else {
            file.inputStream().use {
                try {
                    mappingReaderIntl(file.name, it.bufferedReader(), input)
                } catch (e: Exception) {
                    throw IOException("Error reading ${file.name}", e)
                }
            }
        }
    }

    fun mappingStream(name: String, stream: InputStream, input: MappingInputBuilder) {
        checkFrozen()
        mappingReaderIntl(name, stream.bufferedReader(), input)
    }

    private fun detectHeader(reader: BufferedReader, fName: String): BetterMappingFormat? {
        val result = when (fName) {
            "parchment.json" -> BetterMappingFormat.PARCHMENT
            "mappings/package.srg" -> BetterMappingFormat.CSRG
            else -> {
                if (fName.endsWith(".json")) return null
//                println("detecting $fName")
                if (fName.endsWith(".csrg")) {
                    if (fName.endsWith("-cl.csrg")) {
                        BetterMappingFormat.CSRG
                    } else if (fName.endsWith("-members.csrg")) {
                        BetterMappingFormat.CSRG
                    } else {
                        null
                    }
                } else {
                    reader.mark(4096)
                    val str = CharArray(4096).let {
                        val read = reader.read(it)
                        if (read == -1) return null // empty file
                        String(it, 0, read)
                    }
                    val type = when (str.substring(0..2)) {
                        "v1\t" -> BetterMappingFormat.TINY
                        "tin" -> BetterMappingFormat.TINY_2
                        "PK:", "CL:", "FD:", "MD:" -> BetterMappingFormat.SRG
                        ".cl", ".pa", ".me", ".fi", ".op" -> BetterMappingFormat.RETROGUARD
                        else -> {
                            if (str.startsWith("tsrg2")) {
                                BetterMappingFormat.TSRG_2
                            } else if (str.startsWith("searge,name") || str.startsWith("param,name") || str.startsWith("class,package")) {
                                BetterMappingFormat.MCP
                            } else if (str.split("\n")[0].contains("\"name\",\"notch\"")) {
                                BetterMappingFormat.OLD_MCP
                            } else if (str.contains("Name,Notes")) {
                                BetterMappingFormat.OLDER_MCP
                            } else if (str.contains("\n\t")) {
                                BetterMappingFormat.TSRG
                            } else if (str.contains(" -> ")) {
                                BetterMappingFormat.PROGUARD
                            } else {
                                null
                            }
                        }
                    }
                    reader.reset()
                    type
                }
            }
        }
//        println("detected $result")
        return result
    }

    private fun mappingReaderIntl(
        fname: String,
        reader: BufferedReader,
        inputs: MappingInputBuilder,
        type: BetterMappingFormat = detectHeader(reader, fname)
            ?: throw IllegalArgumentException("cannot detect mapping format for ${fname}, first line: ${reader.lines().findFirst().orElse(null)}")
    ) {
        val input = inputs.build(fname, type)
        if (input.exclude) return
        @Suppress("NAME_SHADOWING")
        input.wrapInput(fname, reader) { reader ->
            reader!!
            println("processing $fname")
            val visitor = MappingNsRenamer(
                MappingSourceNsSwitch(
                    globalFMV(input.fmv(
                        MappingDstNsFilter(
                            tree, input.nsFilter
                        ), tree, side
                    )),
                    input.nsSource
                ), input.nsMap
            )
            val preDstNs = tree.dstNamespaces ?: emptyList()
            when (type) {
                BetterMappingFormat.TINY -> Tiny1Reader.read(reader, visitor)
                BetterMappingFormat.TINY_2 -> Tiny2Reader.read(reader, visitor)
                BetterMappingFormat.MCP -> {
                    when (fname.split("/", "\\").last()) {
                        "methods.csv" -> {
                            MCPReader.readMethod(
                                side, reader, "searge", "mcp", tree, visitor
                            )
                        }

                        "fields.csv" -> {
                            MCPReader.readField(
                                side, reader, "searge", "mcp", tree, visitor
                            )
                        }

                        "params.csv" -> {
                            MCPReader.readParam(
                                side, reader, "searge", "mcp", tree, visitor
                            )
                        }

                        "packages.csv" -> {
                            reprocessWithAddedGlobalFMV(MCPReader.readPackages(
                                side, reader, setOf("searge", input.nsMap["mcp"] ?: "mcp")
                            ))
                        }

                        else -> throw IllegalArgumentException("cannot process mapping format $type for $fname")
                    }
                }

                BetterMappingFormat.OLD_MCP -> {
                    when (fname.split("/", "\\").last()) {
                        "classes.csv" -> {
                            OldMCPReader.readClasses(
                                side, reader, "official", "searge", "mcp", visitor
                            )
                        }

                        "methods.csv" -> {
                            OldMCPReader.readMethod(
                                side, reader, "official", "searge", "mcp", visitor, tree
                            )
                        }

                        "fields.csv" -> {
                            OldMCPReader.readField(
                                side, reader, "official", "searge", "mcp", visitor, tree
                            )
                        }

                        else -> throw IllegalArgumentException("cannot process mapping format $type for $fname")
                    }
                }

                BetterMappingFormat.OLDER_MCP -> {
                    when (fname.split("/", "\\").last()) {
                        "methods.csv" -> {
                            OlderMCPReader.readMethod(
                                side, reader, "searge", "mcp", tree, visitor
                            )
                        }

                        "fields.csv" -> {
                            OlderMCPReader.readField(
                                side, reader, "searge", "mcp", tree, visitor
                            )
                        }

                        else -> throw IllegalArgumentException("cannot process mapping format $type for $fname")
                    }
                }

                BetterMappingFormat.SRG -> SrgReader.read(reader, "official", "searge", visitor)
                BetterMappingFormat.TSRG -> TsrgReader.read(reader, "official", "searge", visitor)
                BetterMappingFormat.TSRG_2 -> TsrgReader.read(reader, visitor)
                BetterMappingFormat.RETROGUARD -> RGSReader.read(reader, "official", "searge", visitor)
                BetterMappingFormat.PROGUARD -> ProGuardReader.read(reader, "mojmap", "official", visitor)
                BetterMappingFormat.PARCHMENT -> ParchmentReader.read(reader, "mojmap", visitor)
                BetterMappingFormat.CSRG -> {
                    when (fname.split("/", "\\", "-").last()) {
                        "cl.csrg" -> {
                            CSrgReader.readClasses(reader, "official", "spigot", visitor)
                        }
                        "members.csrg" -> {
                            CSrgReader.readMembers(reader, "official", "spigot", visitor, tree)
                        }
                        "package.srg" -> {
                            reprocessWithAddedGlobalFMV(CSrgReader.readPackages(
                                reader, setOf(input.nsMap["spigot"] ?: "spigot")
                            ))
                        }
                        else -> throw IllegalArgumentException("cannot process mapping format $type for $fname")
                    }
                }
                else -> {
                    throw IllegalArgumentException("cannot process mapping format $type")
                }
            }
            val postDstNs = tree.dstNamespaces ?: emptyList()
            ns.addAll(postDstNs - preDstNs.toSet())

            input.afterRemap(tree)
        }
    }

    fun build(): MappingTreeView {
        if (!frozen) frozen = true
        for (action in onBuild) {
            action.action()
        }
        return tree
    }

    class MappingInputBuilder {
        class MappingInput {
            val nsMap: MutableMap<String, String> = mutableMapOf()
            val nsFilter: MutableSet<String> = mutableSetOf()
            var fmv: (MappingVisitor, MappingTreeView, EnvType) -> MappingVisitor = { v, _, _ -> v }
            var nsSource: String = "official"
            var afterRemap: (MappingTree) -> Unit = { }
            val dependsOn: MutableSet<String> = mutableSetOf()
            var allowDuplicate: Boolean = false
            var exclude: Boolean = false

            fun mapNs(from: String, to: String) {
                nsMap[from] = to
            }

            fun addNs(ns: String) {
                nsFilter.add(ns)
            }

            fun setSource(ns: String) {
                nsSource = ns
            }

            fun forwardVisitor(f: (MappingVisitor) -> MappingVisitor) {
                val prev = fmv
                fmv = { v, m, e -> f(prev(v, m, e)) }
            }

            fun forwardVisitor(f: (MappingVisitor, MappingTreeView) -> MappingVisitor) {
                val prev = fmv
                fmv = { v, m, e -> f(prev(v, m, e), m) }
            }

            fun forwardVisitor(f: (MappingVisitor, MappingTreeView, EnvType) -> MappingVisitor) {
                val prev = fmv
                fmv = { v, m, e -> f(prev(v, m, e), m, e) }
            }

            fun clearForwardVisitor() {
                fmv = { v, _, _ -> v }
            }

            fun afterRemap(f: (MappingTree) -> Unit) {
                val prev = afterRemap
                afterRemap = { m -> prev(m); f(m) }
            }

            fun clearAfterRemap() {
                afterRemap = { }
            }

            fun addDepends(ns: String) {
                dependsOn.add(ns)
            }

            fun exclude() {
                exclude = true
            }

            fun clearExclude() {
                exclude = false
            }
        }

        private var frozen by FinalizeOnWrite(false)
        private val provided = mutableListOf<Pair<(String, BetterMappingFormat) -> Boolean, MappingInput.() -> Unit>>()

        fun checkFrozen() {
            if (frozen) throw IllegalStateException("cannot modify frozen builder")
        }

        fun provides(f: (String, BetterMappingFormat) -> Boolean, input: MappingInput.() -> Unit) {
            checkFrozen()
            provided.add(f to input)
        }

        fun clearProvides() {
            checkFrozen()
            provided.clear()
        }

        fun build(fname: String, format: BetterMappingFormat): MappingInput {
            if (!frozen) frozen = true
            val input = MappingInput()
            for ((f, i) in provided) {
                if (f(fname, format)) {
                    input.i()
                }
            }
            return input
        }
    }

    enum class BetterMappingFormat {
        TINY, TINY_2,
        ENIGMA,
        SRG,
        CSRG,
        TSRG, TSRG_2,
        RETROGUARD,
        MCP, OLD_MCP, OLDER_MCP,
        PROGUARD, PARCHMENT,
        OBF_JAR;
    }
}