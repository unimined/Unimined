package xyz.wagyourtail.unimined.remapper.access

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.commonskt.reader.StringCharReader
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.at.ATReader
import xyz.wagyourtail.unimined.mapping.formats.at.LegacyATReader
import xyz.wagyourtail.unimined.mapping.formats.aw.AWReader
import xyz.wagyourtail.unimined.mapping.jvms.four.AccessFlag
import xyz.wagyourtail.unimined.mapping.mappingTree
import java.nio.file.Files
import java.nio.file.Path

class AccessFormatConvertTest {

    @TempDir
    lateinit var dir: Path

    private val named = Namespace("named")

    /** dev-ns tree — `at2aw` resolves field descs + wildcards against it. */
    private val tree = mappingTree("official", "named") {
        clazz("a/Target", "example/Target") {
            field("a;I", "value;I")
            field("b;Ljava/lang/String;", "name;Ljava/lang/String;")
            method("a;()V", "run;()V")
            method("b;()I", "tick;()I")
        }
    }

    /** `cls` for class rows, `cls name desc` for member rows — FQN's
     *  serialized `Lcls;` form isn't what assertions read naturally. */
    private fun fqnText(target: xyz.wagyourtail.unimined.mapping.jvms.ext
            .FullyQualifiedName): String {
        val (cls, nad) = target.getParts()
        val base = cls.getInternalName().value
        val (name, desc) = nad?.getParts() ?: (null to null)
        return if (name == null) base
        else "$base ${name.value}${desc?.let { " $it" } ?: ""}"
    }

    private fun awRows(bytes: ByteArray): List<Pair<String, String>> =
        AWReader.readData(StringCharReader(bytes.decodeToString()), true)
            .targets.filterIsInstance<AWReader.AWData>()
            .map { it.access to fqnText(it.target) }

    private fun atRows(bytes: ByteArray): List<ATReader.ATData> =
        ATReader.readData(StringCharReader(bytes.decodeToString()), true)
            .filterIsInstance<ATReader.ATData>()

    private fun legacyAtRows(bytes: ByteArray): List<ATReader.ATData> =
        LegacyATReader.readData(StringCharReader(bytes.decodeToString()), true)
            .filterIsInstance<ATReader.ATData>()

    // ---------------------------------------------------------------
    // detect
    // ---------------------------------------------------------------

    @Test
    fun `detect reads extension then content header`() {
        assertEquals(
            AccessFormatConvert.Format.AW,
            AccessFormatConvert.detect(
                dir.resolve("mod.accesswidener"), "anything".toByteArray()))
        assertEquals(
            AccessFormatConvert.Format.AT,
            AccessFormatConvert.detect(
                dir.resolve("META-INF/accesstransformer.cfg"),
                "anything".toByteArray()))
        assertEquals(
            AccessFormatConvert.Format.AW,
            AccessFormatConvert.detect(
                null,
                "# comment\naccessWidener v1 named\n".toByteArray()))
        assertEquals(
            AccessFormatConvert.Format.AT,
            AccessFormatConvert.detect(
                null,
                "public example.Target\n".toByteArray()))
        assertNull(
            AccessFormatConvert.detect(
                dir.resolve("notes.txt"), "hello".toByteArray()))
    }

    // ---------------------------------------------------------------
    // aw2at
    // ---------------------------------------------------------------

    @Test
    fun `aw2at maps accessible extendable and mutable rows`() {
        val out = AccessFormatConvert.aw2at(
            """
            accessWidener v1 named
            # keep me
            accessible class example/Target
            extendable class example/Base
            accessible method example/Target run ()V
            extendable method example/Target tick ()V
            mutable field example/Target value I
            accessible field example/Target name Ljava/lang/String;
            transitive-accessible class example/Other
            """.trimIndent().toByteArray()
        )
        val rows = atRows(out)
        fun row(name: String?, cls: String) = rows.filter {
            it.targetClass.value == cls && when (it) {
                is ATReader.ATDataMethod -> it.memberName.value == name
                is ATReader.ATDataField -> it.memberName.value == name
                else -> name == null
            }
        }
        // accessible class → public
        assertTrue(row(null, "example/Target").any {
            it.access == AccessFlag.PUBLIC &&
                    it.final == ATReader.TriState.LEAVE
        })
        // extendable class → public -f
        assertTrue(row(null, "example/Base").any {
            it.access == AccessFlag.PUBLIC &&
                    it.final == ATReader.TriState.REMOVE
        })
        // accessible method → public; extendable method → protected -f
        assertTrue(row("run", "example/Target").any {
            it.access == AccessFlag.PUBLIC &&
                    it.final == ATReader.TriState.LEAVE
        })
        assertTrue(row("tick", "example/Target").any {
            it.access == AccessFlag.PROTECTED &&
                    it.final == ATReader.TriState.REMOVE
        })
        // mutable field → public -f (bare -f rows aren't valid AT)
        assertTrue(row("value", "example/Target").any {
            it.access == AccessFlag.PUBLIC &&
                    it.final == ATReader.TriState.REMOVE
        })
        // field descs drop — AT fields are name-only
        assertTrue(row("name", "example/Target").all {
            it is ATReader.ATDataField
        })
        // transitive- prefix strips
        assertTrue(row(null, "example/Other").isNotEmpty())
        // comment survives as an ATComment
        assertTrue(
            ATReader.readData(StringCharReader(out.decodeToString()), true)
                .filterIsInstance<ATReader.ATComment>()
                .any { it.comment.contains("keep me") })
    }

    @Test
    fun `aw2at merges repeated targets to weakest access`() {
        val out = AccessFormatConvert.aw2at(
            """
            accessWidener v1 named
            extendable method example/Target tick ()V
            accessible method example/Target tick ()V
            """.trimIndent().toByteArray()
        )
        // accessible + extendable on the same target → public -f
        val rows = atRows(out).filterIsInstance<ATReader.ATDataMethod>()
        assertEquals(1, rows.size)
        assertEquals(AccessFlag.PUBLIC, rows[0].access)
        assertEquals(ATReader.TriState.REMOVE, rows[0].final)
    }

    @Test
    fun `aw2at legacy emits legacy rows`() {
        val out = AccessFormatConvert.aw2at(
            "accessWidener v1 named\naccessible class example/Target\n"
                .toByteArray(),
            legacy = true
        )
        assertTrue(legacyAtRows(out).isNotEmpty())
    }

    @Test
    fun `aw2at rejects unexpressible verbs`() {
        assertThrows(IllegalArgumentException::class.java) {
            AccessFormatConvert.aw2at(
                "accessWidener v1 named\nmutable class example/Target\n"
                    .toByteArray())
        }
        // warn-mode drops the row instead of throwing
        val warnings = mutableListOf<String>()
        val out = AccessFormatConvert.aw2at(
            "accessWidener v1 named\nmutable class example/Target\n"
                .toByteArray(),
            warn = { warnings += it })
        assertEquals(1, warnings.size)
        assertTrue(atRows(out).isEmpty())
    }

    // ---------------------------------------------------------------
    // at2aw
    // ---------------------------------------------------------------

    @Test
    fun `at2aw maps access and final via the tree`() {
        val out = AccessFormatConvert.at2aw(
            """
            public example.Target
            public-f example.Target value
            protected example.Target run()V
            public-f example.Target run()V
            """.trimIndent().toByteArray(),
            legacy = false, tree = tree, targetNs = named
        )
        val rows = awRows(out).toSet()
        // class public → accessible
        assertTrue("accessible" to "example/Target" in rows)
        // field public -f → accessible + mutable
        assertTrue("accessible" to "example/Target value I" in rows)
        assertTrue("mutable" to "example/Target value I" in rows)
        // method protected → extendable
        assertTrue("extendable" to "example/Target run ()V" in rows)
        // method public -f → accessible + extendable
        assertTrue("accessible" to "example/Target run ()V" in rows)
        // header carries the target namespace (v2 — UML's canonical
        // emission; whitespace is tabs)
        assertTrue(
            out.decodeToString().startsWith("accessWidener\tv2\tnamed"))
    }

    @Test
    fun `at2aw skips narrowing and unresolvable rows`() {
        val warnings = mutableListOf<String>()
        val out = AccessFormatConvert.at2aw(
            """
            private example.Target
            public example.Target missing
            """.trimIndent().toByteArray(),
            legacy = false, tree = tree, targetNs = named,
            warn = { warnings += it }
        )
        // private-narrowing class + tree-unresolvable field both drop
        assertEquals(2, warnings.size)
        assertTrue(awRows(out).isEmpty())
    }

    @Test
    fun `at2aw expands field wildcards through the tree`() {
        val out = AccessFormatConvert.at2aw(
            "public example.Target *\n".toByteArray(),
            legacy = false, tree = tree, targetNs = named
        )
        val rows = awRows(out).toSet()
        assertTrue("accessible" to "example/Target value I" in rows)
        assertTrue(
            "accessible" to "example/Target name Ljava/lang/String;" in rows)
        // methods untouched by a field wildcard
        assertFalse(rows.any { it.second.contains("(") })
    }

    @Test
    fun `at2aw handles legacy AT input`() {
        // legacy rows join class+member on a dot — `cls.member` /
        // `cls.member(desc)`; class rows stay slash-separated
        val out = AccessFormatConvert.at2aw(
            "public example/Target\npublic-f example/Target.value\n"
                .toByteArray(),
            legacy = true, tree = tree, targetNs = named
        )
        val rows = awRows(out).toSet()
        assertTrue("accessible" to "example/Target" in rows)
        assertTrue("accessible" to "example/Target value I" in rows)
        assertTrue("mutable" to "example/Target value I" in rows)
    }

    @Test
    fun `at2aw without a tree fails clearly`() {
        assertThrows(IllegalArgumentException::class.java) {
            AccessFormatConvert.convert(
                "public example.Target\n".toByteArray(),
                AccessFormatConvert.Format.AT,
                AccessFormatConvert.Format.AW)
        }
    }

    // ---------------------------------------------------------------
    // mergeAws
    // ---------------------------------------------------------------

    @Test
    fun `mergeAws dedups rows and re-heads the namespace`() {
        val merged = AccessFormatConvert.mergeAws(
            listOf(
                "accessWidener v1 named\naccessible class a/A\naccessible class b/B\n"
                    .toByteArray(),
                "accessWidener v1 other\naccessible class a/A\naccessible class c/C\n"
                    .toByteArray()
            ),
            named
        )
        val rows = awRows(merged).toSet()
        assertEquals(
            setOf(
                "accessible" to "a/A",
                "accessible" to "b/B",
                "accessible" to "c/C"
            ), rows)
        assertTrue(
            merged.decodeToString().startsWith("accessWidener\tv2\tnamed"))
    }

    // ---------------------------------------------------------------
    // convert() dispatch + file path
    // ---------------------------------------------------------------

    @Test
    fun `convert dispatches and same-format passes through`() {
        val bytes = "accessWidener v1 named\naccessible class a/A\n".toByteArray()
        assertArrayEquals(
            bytes, AccessFormatConvert.convert(
                bytes, AccessFormatConvert.Format.AW,
                AccessFormatConvert.Format.AW))
        val f = dir.resolve("in.accesswidener")
        Files.write(f, bytes)
        val at = AccessFormatConvert.convert(
            f, AccessFormatConvert.Format.AW, AccessFormatConvert.Format.AT)
        assertTrue(atRows(at).any {
            it.targetClass.value == "a/A" &&
                    it.access == AccessFlag.PUBLIC
        })
    }

}
