package xyz.wagyourtail.unimined.remapper.uml

import org.junit.jupiter.api.Test
import xyz.wagyourtail.commonskt.reader.StringCharReader
import xyz.wagyourtail.commonskt.utils.toHex
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.envView
import xyz.wagyourtail.unimined.mapping.formats.umf.UMFReader
import xyz.wagyourtail.unimined.mapping.remapper.asm.AsmRemapper
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import kotlinx.coroutines.runBlocking
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists


/**
 * Repro for the World.m_4155590 collapse — runs the exact preMerge
 * remap the merge hook performs (clientOfficial → calamus) on the
 * real b1.7.3 client jar + real composed bundle, then counts
 * intermediary-name occurrences on World's (fd) methods.
 */
class MergeRemapReproTest {

    @Test
    fun dumpServerWorldMembers() {
        val bundle = java.nio.file.Path.of(
            "/run/media/william/m2shared/Projects/unimined-org/unimined/testing/ornithe-b173/build/unimined/mappings/main/prod-jar.json")
        val payload = ZipFile(bundle.toFile()).use { z ->
            z.getInputStream(z.getEntry("views/uml-tree")).readBytes()
        }
        val tree = MutableMappingTree()
        UMFReader.readNonBlocking(
            input = StringCharReader(payload.decodeToString().replace("\r", "")),
            context = null, into = tree, nsMapping = emptyMap())
        val cls = tree.classes.firstOrNull { c ->
            c.names.any { it.value.value == "net/minecraft/unmapped/C_5553933" }
        } ?: error("no C_5553933")
        println("entries: ${cls.names.map { "${it.key.name}=${it.value.value}" }}")
        val sNs = tree.namespaces.first { it.name == "serverOfficial" }
        val cNs = tree.namespaces.first { it.name == "calamus" }
        val groups = cls.methods.groupBy { it.entries[sNs]?.name?.value }
        for ((name, ms) in groups.entries.sortedBy { it.key }) {
            if (ms.size > 1 || name == "b" || name == "c" || name == "a") {
                println("$name: ${ms.size}")
                ms.forEach { m ->
                    println("  sDesc=${m.entries[sNs]?.descriptor?.value} cDesc=${m.entries[cNs]?.descriptor?.value} hasDesc=${m.hasDescriptor()} cal=${m.entries[cNs]?.name?.value}")
                }
            }
        }
        // probe: getMethod(serverOfficial, "a", jarDesc) — what does it resolve?
        // jarDescs from the server jar's dj.a overloads (server-obf types)
        val aMs = cls.methods.filter { it.entries[sNs]?.name?.value == "a" }
        println("a members: ${aMs.size}")
        aMs.take(6).forEach { m ->
            // simulate the lookup with each member's OWN calamus desc remapped —
            // approximates the server jar desc for that overload family
            val probeDesc = with(tree) { m.getDescriptor(sNs) }
            println("  probe getDesc(serverOfficial)=${probeDesc?.value} for cal=${m.entries[cNs]?.name?.value}")
        }
    }

    @Test
    fun realClientRemapKeepsOverloadsDistinct() = remapSide("client", "clientOfficial")

    @Test
    fun realServerRemapKeepsOverloadsDistinct() = remapSide("server", "serverOfficial")

    @Test
    fun realMergeBundleServerEnvRemap() {
        val inputJar = java.nio.file.Path.of(
            "/home/william/.gradle/caches/unimined/native-cache/versions/b1.7.3/server.jar")
        val bundle = java.nio.file.Path.of(
            "/home/william/.gradle/caches/unimined/mapping-cache/mappings-merged/2ab8223058947ab18923730ad2832fd6e200a55e.json")
        val payload = ZipFile(bundle.toFile()).use { z ->
            z.getInputStream(z.getEntry("views/uml-tree")).readBytes()
        }
        val tree = MutableMappingTree()
        UMFReader.readNonBlocking(
            input = StringCharReader(payload.decodeToString().replace("\r", "")),
            context = null, into = tree, nsMapping = emptyMap())
        // probe the envView'd tree before remapping
        run {
            val view = tree.envView("server")
            val oNs = view.namespaces.first { it.name == "official" }
            val cls = view.classes.firstOrNull { c ->
                c.names.any { it.value.value == "net/minecraft/unmapped/C_5553933" }
            } ?: error("no C_5553933 in envView")
            val aMs = with(view) { cls.getMethods(oNs, xyz.wagyourtail.unimined.mapping.jvms.four.two.two.UnqualifiedName.unchecked("a"), null) }
            println("[probe] server-view a members: ${aMs.size}")
            aMs.take(8).forEach { m ->
                println("  descs=${m.descs.map { "${it.key.name}=${it.value.value}" }} hasDesc=${m.hasDescriptor()} projDesc=${with(view) { m.getDescriptor(oNs) }?.value} cal=${m.names.values.firstOrNull { n -> n.value.startsWith("m_") }?.value}")
            }
            // per-desc resolution probe: feed the server jar's actual 'a'
            // descs through getMethod and print the winning member
            JarFile(inputJar.toFile()).use { jar ->
                val node = ClassNode()
                ClassReader(jar.getInputStream(jar.getJarEntry("dj.class"))).accept(node, 0)
                val wins = mutableMapOf<String, Int>()
                val detail = mutableMapOf<String, MutableList<Pair<String, String>>>()
                node.methods.filter { it.name != "<init>" && it.name != "<clinit>" }.forEach { mm ->
                    val hit = with(view) {
                        cls.getMethod(oNs,
                            xyz.wagyourtail.unimined.mapping.jvms.four.two.two.UnqualifiedName.unchecked(mm.name),
                            xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor.unchecked(mm.desc))
                    }
                    val cal = hit?.names?.values?.firstOrNull { n -> n.value.startsWith("m_") }?.value
                        ?: hit?.names?.values?.firstOrNull()?.value ?: "KEPT"
                    wins[cal] = (wins[cal] ?: 0) + 1
                    detail.getOrPut(cal) { mutableListOf() }.add(mm.name to mm.desc)
                }
                println("[resolve] winners: $wins")
                detail.filter { it.key != "KEPT" && it.value.size > 1 }.forEach { (cal, ms) ->
                    println("  collapse $cal <- ${ms.take(6)}")
                }
                // full trace: for every KEPT query, show what the bundle's
                // same-name rows project as official descs vs the real desc
                detail["KEPT"]?.forEach { (n, d) ->
                    val rows = with(view) {
                        cls.getMethods(oNs,
                            xyz.wagyourtail.unimined.mapping.jvms.four.two.two.UnqualifiedName.unchecked(n), null)
                    }
                    val offers = rows.map { m ->
                        val pd = with(view) { m.getDescriptor(oNs) }?.value
                        "$pd→${m.names.values.firstOrNull { it.value.startsWith("m_") }?.value}"
                    }
                    println("  KEPT $n$d  offers=$offers")
                }
            }
        }
        val out = createTempFile("remap-merge-", ".jar")
        try {
            UmlJarRemapper().remap(
                inputJar, out, UmlMappingData(tree),
                "official", "calamus", "server")
            JarFile(out.toFile()).use { jar ->
                val e = jar.entries().asSequence()
                    .first { it.name.endsWith("C_5553933.class") }
                val node = ClassNode()
                ClassReader(jar.getInputStream(e)).accept(node, 0)
                val counts = node.methods.groupingBy { it.name }.eachCount()
                val dup = counts.filterValues { it > 1 }
                println("[merge-bundle] methods: ${node.methods.size}, dup names: $dup")
            }
        } finally {
            out.deleteIfExists()
        }
    }

    @Test
    fun realMergeBundleClientEnvRemap() {
        val inputJar = java.nio.file.Path.of(
            "/home/william/.gradle/caches/unimined/native-cache/versions/b1.7.3/client.jar")
        val bundle = java.nio.file.Path.of(
            "/home/william/.gradle/caches/unimined/mapping-cache/mappings-merged/2ab8223058947ab18923730ad2832fd6e200a55e.json")
        val payload = ZipFile(bundle.toFile()).use { z ->
            z.getInputStream(z.getEntry("views/uml-tree")).readBytes()
        }
        val tree = MutableMappingTree()
        UMFReader.readNonBlocking(
            input = StringCharReader(payload.decodeToString().replace("\r", "")),
            context = null, into = tree, nsMapping = emptyMap())
        run {
            val view = tree.envView("client")
            val oNs = view.namespaces.first { it.name == "official" }
            val cls = view.classes.firstOrNull { c ->
                c.names.any { it.value.value == "net/minecraft/unmapped/C_5553933" }
            } ?: error("no C_5553933 in envView(client)")
            JarFile(inputJar.toFile()).use { jar ->
                val node = ClassNode()
                ClassReader(jar.getInputStream(jar.getJarEntry("fd.class"))).accept(node, 0)
                val wins = mutableMapOf<String, Int>()
                val detail = mutableMapOf<String, MutableList<Pair<String, String>>>()
                node.methods.filter { it.name != "<init>" && it.name != "<clinit>" }.forEach { mm ->
                    val hit = with(view) {
                        cls.getMethod(oNs,
                            xyz.wagyourtail.unimined.mapping.jvms.four.two.two.UnqualifiedName.unchecked(mm.name),
                            xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor.unchecked(mm.desc))
                    }
                    val cal = hit?.names?.values?.firstOrNull { n -> n.value.startsWith("m_") }?.value
                        ?: hit?.names?.values?.firstOrNull()?.value ?: "KEPT"
                    wins[cal] = (wins[cal] ?: 0) + 1
                    detail.getOrPut(cal) { mutableListOf() }.add(mm.name to mm.desc)
                }
                println("[resolve-client] winners: $wins")
                detail.filter { it.key != "KEPT" && it.value.size > 1 }.forEach { (cal, ms) ->
                    println("  collapse $cal <- ${ms.take(9)}")
                }
            }
        }
        val out = createTempFile("remap-merge-client-", ".jar")
        try {
            UmlJarRemapper().remap(
                inputJar, out, UmlMappingData(tree),
                "official", "calamus", "client")
            JarFile(out.toFile()).use { jar ->
                val e = jar.entries().asSequence()
                    .first { it.name.endsWith("C_5553933.class") }
                val node = ClassNode()
                ClassReader(jar.getInputStream(e)).accept(node, 0)
                val counts = node.methods.groupingBy { it.name }.eachCount()
                val dup = counts.filterValues { it > 1 }
                println("[merge-bundle-client] methods: ${node.methods.size}, dup names: $dup")
                val sigs = node.methods.groupingBy { it.name + it.desc }.eachCount()
                val dupSigs = sigs.filterValues { it > 1 }
                println("[merge-bundle-client] dup (name,desc): $dupSigs")
            }
        } finally {
            out.deleteIfExists()
        }
    }

    @Test
    fun graphRemapClientKeepsOverloadsDistinct() {
        val clientJar = java.nio.file.Path.of(
            "/home/william/.gradle/caches/unimined/native-cache/versions/b1.7.3/client.jar")
        val bundle = java.nio.file.Path.of(
            "/run/media/william/m2shared/Projects/unimined-org/unimined/testing/ornithe-b173/build/unimined/mappings/main/prod-jar.json")
        val payload = ZipFile(bundle.toFile()).use { z ->
            z.getInputStream(z.getEntry("views/uml-tree")).readBytes()
        }
        val tree = MutableMappingTree()
        UMFReader.readNonBlocking(
            input = StringCharReader(payload.decodeToString().replace("\r", "")),
            context = null,
            into = tree,
            nsMapping = emptyMap()
        )
        val out = createTempFile("remap-graph-", ".jar")
        try {
            runBlocking {
                AsmRemapper.remap(
                    clientJar, out, listOf(tree),
                    Namespace("clientOfficial"), Namespace("calamus"),
                    libraries = setOf(clientJar))
            }
            JarFile(out.toFile()).use { jar ->
                val e = jar.entries().asSequence()
                    .first { it.name.endsWith("C_5553933.class") }
                val node = ClassNode()
                ClassReader(jar.getInputStream(e)).accept(node, 0)
                val counts = node.methods.groupingBy { it.name }.eachCount()
                val dup = counts.filterValues { it > 1 }
                println("[graph] methods: ${node.methods.size}, dup names: $dup")
                node.methods.filter { it.name == "m_8410365" || it.name == "m_4155590" }
                    .forEach { println("  ${it.name}${it.desc}") }
            }
        } finally {
            out.deleteIfExists()
        }
    }

    private fun remapSide(side: String, fromNs: String) {
        val inputJar = java.nio.file.Path.of(
            "/home/william/.gradle/caches/unimined/native-cache/versions/b1.7.3/$side.jar")
        val bundle = java.nio.file.Path.of(
            "/run/media/william/m2shared/Projects/unimined-org/unimined/testing/ornithe-b173/build/unimined/mappings/main/prod-jar.json")

        // prod-jar.json is a zip of views — views/uml-tree is a UMF doc
        val payload = ZipFile(bundle.toFile()).use { z ->
            z.getInputStream(z.getEntry("views/uml-tree")).readBytes()
        }
        val tree = MutableMappingTree()
        UMFReader.readNonBlocking(
            input = StringCharReader(payload.decodeToString().replace("\r", "")),
            context = null,
            into = tree,
            nsMapping = emptyMap()
        )

        val out = createTempFile("remap-", ".jar")
        try {
            UmlJarRemapper().remap(
                inputJar, out, UmlMappingData(tree),
                fromNs, "calamus", null)
            val sha1 = java.security.MessageDigest.getInstance("SHA-1")
                .digest(java.nio.file.Files.readAllBytes(out))
                .toHex()
            println("[$side] output sha1: $sha1")
            JarFile(out.toFile()).use { jar ->
                // calamus renames World → C_5553933
                val e = jar.entries().asSequence()
                    .first { it.name.endsWith("C_5553933.class") }
                val node = ClassNode()
                ClassReader(jar.getInputStream(e)).accept(node, 0)
                val counts = node.methods.groupingBy { it.name }.eachCount()
                val dup = counts.filterValues { it > 1 }
                println("[$side] methods: ${node.methods.size}, dup names: $dup")
                node.methods.filter { it.name.startsWith("m_") }
                    .sortedBy { it.name }
                    .forEach { println("  ${it.name}${it.desc}") }
            }
        } finally {
            out.deleteIfExists()
        }
    }
}
