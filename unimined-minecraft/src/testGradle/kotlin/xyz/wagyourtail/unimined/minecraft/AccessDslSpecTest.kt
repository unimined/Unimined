package xyz.wagyourtail.unimined.minecraft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `access { }` semantic rules emit in each consumed document format —
 * AW directives on AW/CT docs, FML entries on AT docs, with
 * conversion or recorded drops where a format can't express a rule.
 */
class AccessDslSpecTest {

    private fun spec(block: AccessDslSpec.() -> Unit) =
        AccessDslSpec().apply(block)

    @Test
    fun `aw directives emit on AW and CT`() {
        val s = spec {
            accessible("net/minecraft/Foo")
            accessible("net/minecraft/Foo", "run", "()V")
            extendable("net/minecraft/Bar")
            mutable("net/minecraft/Bar", "count", "I")
            transitiveAccessible("net/minecraft/Baz")
        }
        val lines = s.emit(AccessDslSpec.Format.AW)
        assertEquals(
            listOf(
                "accessible\tclass\tnet/minecraft/Foo",
                "accessible\tmethod\tnet/minecraft/Foo\trun\t()V",
                "extendable\tclass\tnet/minecraft/Bar",
                "mutable\tfield\tnet/minecraft/Bar\tcount\tI",
                "transitive-accessible\tclass\tnet/minecraft/Baz",
            ), lines
        )
        // CT is an AW superset — identical directives
        assertEquals(lines, s.emit(AccessDslSpec.Format.CT))
        assertFalse(s.needsClassTweaker)
    }

    @Test
    fun `member kind inferred from descriptor`() {
        val s = spec {
            accessible("a/B", "field", "I")
            accessible("a/B", "method", "(I)V")
        }
        val lines = s.emit(AccessDslSpec.Format.AW)
        assertTrue(lines[0].startsWith("accessible\tfield"))
        assertTrue(lines[1].startsWith("accessible\tmethod"))
    }

    @Test
    fun `ct directives emit on AW-family and drop on AT`() {
        val s = spec {
            injectInterface("net/minecraft/Foo", "com/example/IFoo")
            transitiveInjectInterface("net/minecraft/Bar", "com/example/IBar")
            extendEnum("net/minecraft/Baz", "EXTRA")
            transitiveExtendEnum("net/minecraft/Baz", "EXTRA2")
        }
        assertTrue(s.needsClassTweaker)
        assertEquals(
            listOf(
                "inject-interface\tnet/minecraft/Foo\tcom/example/IFoo",
                "transitive-inject-interface\tnet/minecraft/Bar\tcom/example/IBar",
                "extend-enum\tnet/minecraft/Baz\tEXTRA",
                "transitive-extend-enum\tnet/minecraft/Baz\tEXTRA2",
            ), s.emit(AccessDslSpec.Format.CT)
        )
        val dropped = mutableListOf<String>()
        val at = s.emit(AccessDslSpec.Format.AT, dropped)
        assertTrue(at.isEmpty())
        assertEquals(4, dropped.size)
    }

    @Test
    fun `aw directives emit as FML entries on AT`() {
        val s = spec {
            accessible("net/minecraft/Foo")
            extendable("net/minecraft/Bar")
            mutable("net/minecraft/Bar", "count", "I")
        }
        assertEquals(
            listOf(
                "public net/minecraft/Foo",
                "protected-f net/minecraft/Bar",
                "public-f net/minecraft/Bar count I",
            ), s.emit(AccessDslSpec.Format.AT)
        )
    }

    @Test
    fun `at entries emit verbatim on AT and convert on AW`() {
        val s = spec {
            at("public", "com/example/Foo")
            at("public-f", "com/example/Bar", "baz", "I")
        }
        assertEquals(
            listOf(
                "public com/example/Foo",
                "public-f com/example/Bar baz I",
            ), s.emit(AccessDslSpec.Format.AT)
        )
        assertEquals(
            listOf(
                "accessible\tclass\tcom/example/Foo",
                "accessible\tfield\tcom/example/Bar\tbaz\tI",
                "mutable\tfield\tcom/example/Bar\tbaz\tI",
            ), s.emit(AccessDslSpec.Format.AW)
        )
    }

    @Test
    fun `at entries with narrowing access or missing desc drop on AW`() {
        val s = spec {
            at("private", "com/example/Foo")
            at("public", "com/example/Bar", "baz")
            at("default", "com/example/Baz")
        }
        val dropped = mutableListOf<String>()
        val aw = s.emit(AccessDslSpec.Format.AW, dropped)
        assertTrue(aw.isEmpty())
        assertEquals(3, dropped.size)
        // all still emit fine on AT
        assertEquals(3, s.emit(AccessDslSpec.Format.AT).size)
    }

    @Test
    fun `at class-level -f emits extendable`() {
        val s = spec { at("protected-f", "com/example/Foo") }
        assertEquals(
            listOf(
                "accessible\tclass\tcom/example/Foo",
                "extendable\tclass\tcom/example/Foo",
            ), s.emit(AccessDslSpec.Format.AW)
        )
    }

    @Test
    fun `from records file args`() {
        val s = spec {
            from("src/main/resources/mod.accesswidener")
            from("build/other.cfg", "third.aw")
        }
        assertEquals(
            listOf("src/main/resources/mod.accesswidener",
                "build/other.cfg", "third.aw"),
            s.files
        )
    }

    @Test
    fun `from block records packaging options`() {
        val s = spec {
            from("extra.cfg") {
                it.output = "META-INF/extra.cfg"
                it.format = "at"
                it.declare = false
            }
        }
        assertEquals(
            listOf(
                AccessDslSpec.NamedFrom(
                    "extra.cfg", "at", "META-INF/extra.cfg", false
                )
            ), s.namedFiles
        )
    }
}
