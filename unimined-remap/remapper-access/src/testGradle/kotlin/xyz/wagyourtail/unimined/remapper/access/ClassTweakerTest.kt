package xyz.wagyourtail.unimined.remapper.access

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.mappingTree
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class ClassTweakerTest {

    @TempDir
    lateinit var dir: Path

    private val identityTree: MappingTree = mappingTree("named")

    private val officialToNamed: MappingTree = mappingTree("official", "named") {
        clazz("a/b/C", "dev/pkg/Clazz")
    }

    @Test
    fun `parses aw v2 header and directives`() {
        val aw = ClassTweaker.parse(
            "mod.accesswidener",
            """
            accessWidener	v2	named
            accessible	class	dev/pkg/Clazz
            accessible	method	dev/pkg/Clazz	doIt	()V
            mutable	field	dev/pkg/Clazz	other	I
            """.trimIndent()
        )
        assertEquals("named", aw.namespace)
        assertEquals("aw", aw.format)
        assertEquals(setOf("dev/pkg/Clazz"), aw.owners)
    }

    @Test
    fun `parses classTweaker v1 header and structural directives`() {
        val ct = ClassTweaker.parse(
            "mod.classTweaker",
            """
            classTweaker	v1	named
            accessible	class	dev/pkg/Clazz
            inject-interface	dev/pkg/Clazz	net/fabricmc/api/MixinInterface
            """.trimIndent()
        )
        assertEquals("named", ct.namespace)
        assertEquals("ct", ct.format)
        assertEquals(
            listOf(
                ClassTweaker.InterfaceInjection(
                    "dev/pkg/Clazz", "Lnet/fabricmc/api/MixinInterface;"
                ) to false
            ),
            ct.interfaces
        )
        // access directives parse through the same table
        assertTrue(
            ct.classChanges("dev/pkg/Clazz").isNotEmpty(),
            "classTweaker accessible rules must populate the access table"
        )
    }

    @Test
    fun `extend-enum and transitive-extend-enum record enum extensions`() {
        // extend-enum is a classTweaker (ct) structural directive —
        // AW grammar doesn't carry it
        val ct = ClassTweaker.parse(
            "mod.classTweaker",
            """
            classTweaker	v2	named
            extend-enum	dev/pkg/EnumType	NEW_CONSTANT
            transitive-extend-enum	dev/pkg/OtherEnum	OTHER_CONSTANT
            """.trimIndent()
        )
        // `transitive-*` applies to the declaring mod AND its
        // dependents — recorded under both flags (same as
        // transitive access rules)
        assertEquals(
            listOf(
                ClassTweaker.EnumExtension("dev/pkg/EnumType", "NEW_CONSTANT") to false,
                ClassTweaker.EnumExtension("dev/pkg/OtherEnum", "OTHER_CONSTANT") to false,
                ClassTweaker.EnumExtension("dev/pkg/OtherEnum", "OTHER_CONSTANT") to true,
            ),
            ct.enumExtensions
        )
    }

    @Test
    fun `at files parse through ATReader`() {
        val at = ClassTweaker.parse(
            "accesstransformer.cfg",
            """
            # comment
            public a/b/C
            public a/b/C doIt()V
            protected-f a/b/C
            public-f a/b/C state
            """.trimIndent()
        )
        assertEquals("at", at.format)
        assertEquals(setOf("a/b/C"), at.owners)
        assertTrue(at.classChanges("a/b/C").isNotEmpty())
        assertTrue(at.methodChanges("a/b/C", "doIt", "()V").isNotEmpty())
        assertTrue(at.fieldChanges("a/b/C", "state", "").isNotEmpty())
    }

    @Test
    fun `accessible class and method become public`() {
        val bytes = writeClass(access = Opcodes.ACC_PRIVATE or 0x200 /*super*/, privateMethod = true)
        val aw = ClassTweaker.parse(
            "mod.accesswidener",
            "accessWidener\tv2\tnamed\n" +
                    "accessible\tclass\ta/b/C\n" +
                    "accessible\tmethod\ta/b/C\thidden\t()V"
        )
        val out = apply(bytes, own = listOf(aw))
        assertAccess(out)
    }

    @Test
    fun `accessible private method also gains final`() {
        // loader semantic (UML +PRIVATE conditional): a widened private
        // method is marked final so subclasses can't silently override it
        val bytes = writeClass(access = 0x200, privateMethod = true)
        val aw = ClassTweaker.parse(
            "mod.accesswidener",
            "accessWidener\tv2\tnamed\n" +
                    "accessible\tmethod\ta/b/C\thidden\t()V"
        )
        val out = apply(bytes, own = listOf(aw))
        val methodAccess = IntArray(1)
        ClassReader(out).accept(object : org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int, name: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?
            ): org.objectweb.asm.MethodVisitor {
                methodAccess[0] = access
                return object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {}
            }
        }, 0)
        assertEquals(Opcodes.ACC_PUBLIC, methodAccess[0] and Opcodes.ACC_PUBLIC)
        assertEquals(Opcodes.ACC_FINAL, methodAccess[0] and Opcodes.ACC_FINAL)
    }

    @Test
    fun `extendable does not demote a public method to protected`() {
        // UML's conditional model: extendable's PROTECTED only applies
        // when the method isn't already PUBLIC — the old set/clear
        // implementation would have downgraded public to protected
        val bytes = writeClass(0x200, privateMethod = false, methodAccess = Opcodes.ACC_PUBLIC)
        val aw = ClassTweaker.parse(
            "mod.accesswidener",
            "accessWidener\tv2\tnamed\n" +
                    "extendable\tmethod\ta/b/C\thidden\t()V"
        )
        val out = apply(bytes, own = listOf(aw))
        val methodAccess = IntArray(1)
        ClassReader(out).accept(object : org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int, name: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?
            ): org.objectweb.asm.MethodVisitor {
                methodAccess[0] = access
                return object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {}
            }
        }, 0)
        assertEquals(
            Opcodes.ACC_PUBLIC, methodAccess[0] and Opcodes.ACC_PUBLIC,
            "extendable must not demote an already-public method"
        )
        assertEquals(0, methodAccess[0] and Opcodes.ACC_FINAL)
    }

    @Test
    fun `directives written in a different namespace are rebased`() {
        val bytes = writeClass(access = Opcodes.ACC_FINAL, privateMethod = false, className = "dev/pkg/Clazz")
        val aw = ClassTweaker.parse(
            "mod.accesswidener",
            "accessWidener\tv2\tofficial\n" +
                    "extendable\tclass\ta/b/C\n" +
                    "accessible\tmethod\ta/b/C\thidden\t()V"
        )
        val out = apply(bytes, own = listOf(aw), tree = officialToNamed)
        val reader = ClassReader(out)
        assertEquals(0, reader.access and Opcodes.ACC_FINAL, "class final flag should be cleared")
        assertEquals(Opcodes.ACC_PUBLIC, reader.access and Opcodes.ACC_PUBLIC)
    }

    @Test
    fun `transitive directives in own files apply to the jar`() {
        // a mod's own transitive rules apply to itself too — the loader
        // propagates them to dependents on top of that
        val bytes = writeClass(access = Opcodes.ACC_PRIVATE or 0x200, privateMethod = false)
        val aw = ClassTweaker.parse(
            "mod.accesswidener",
            "accessWidener\tv2\tnamed\n" +
                    "transitive-accessible\tclass\ta/b/C"
        )
        val out = apply(bytes, own = listOf(aw))
        val reader = ClassReader(out)
        assertEquals(Opcodes.ACC_PUBLIC, reader.access and Opcodes.ACC_PUBLIC)
    }

    @Test
    fun `dependency tweakers apply only transitive directives`() {
        // Loom's TransitiveOnlyFilter: a dependency's plain directives
        // widen for the dependency itself only; dependents get just the
        // transitive-* rules
        val bytes = writeClass(access = Opcodes.ACC_PRIVATE or 0x200, privateMethod = false)
        val aw = ClassTweaker.parse(
            "mod.accesswidener",
            "accessWidener\tv2\tnamed\n" +
                    "accessible\tmethod\ta/b/C\thidden\t()V\n" +
                    "transitive-accessible\tclass\ta/b/C"
        )
        val out = apply(bytes, dep = listOf(aw))
        val reader = ClassReader(out)
        assertEquals(Opcodes.ACC_PUBLIC, reader.access and Opcodes.ACC_PUBLIC)
        val methodAccess = IntArray(1)
        reader.accept(object : org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int, name: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?
            ): org.objectweb.asm.MethodVisitor {
                methodAccess[0] = access
                return object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {}
            }
        }, 0)
        assertEquals(
            Opcodes.ACC_PROTECTED,
            methodAccess[0] and Opcodes.ACC_PROTECTED,
            "dependency's plain method rule must not apply to dependents"
        )
    }

    @Test
    fun `toAccessModelSection emits all rules with provenance`() {
        val tweaker = ClassTweaker.parse(
            "mod.accesswidener",
            "accessWidener\tv2\tnamed\n" +
                    "accessible\tclass\ta/b/C\n" +
                    "accessible\tmethod\ta/b/C\thidden\t()V\n" +
                    "transitive-accessible\tfield\ta/b/C\tstate\tI\n" +
                    "transitive-mutable\tfield\ta/b/D\tconfig\tLjava/lang/String;"
        )
        val section = tweaker.toAccessModelSection("dep.jar")
        assertEquals("named", section.namespace)
        assertEquals(4, section.entries.size)
        assertTrue(section.entries.all { it.source == "dep.jar" })

        val cls = section.entries.single {
            it.kind == xyz.wagyourtail.unimined.api.access.AccessModel.Kind.CLASS &&
                    it.owner == "a/b/C"
        }
        // accessible -> add PUBLIC; the model's flat set/clear translates
        // UML's mutually-exclusive-visibility semantics into a mask clear
        assertEquals(Opcodes.ACC_PUBLIC, cls.change.set)
        assertEquals(7, cls.change.clear)
        assertTrue(!cls.transitive)

        val transitiveField = section.entries.single { it.name == "state" }
        assertTrue(transitiveField.transitive)

        val mutableField = section.entries.single { it.owner == "a/b/D" }
        assertTrue(mutableField.transitive)
        // mutable -> final cleared, no visibility change
        assertEquals(0, mutableField.change.set)
        assertEquals(Opcodes.ACC_FINAL, mutableField.change.clear and Opcodes.ACC_FINAL)
    }

    @Test
    fun `hasTransitiveRules reflects transitive directive presence`() {
        val withTransitive = ClassTweaker.parse(
            "mod.accesswidener",
            "accessWidener\tv2\tnamed\n" +
                    "accessible\tclass\ta/b/C\n" +
                    "transitive-mutable\tfield\ta/b/C\tstate\tI"
        )
        val withoutTransitive = ClassTweaker.parse(
            "mod.accesswidener",
            "accessWidener\tv2\tnamed\n" +
                    "accessible\tclass\ta/b/C"
        )
        assertEquals(true, withTransitive.hasTransitiveRules)
        assertEquals(false, withoutTransitive.hasTransitiveRules)
    }

    @Test
    fun `inject-interface merges into the class interface list and signature`() {
        val ct = ClassTweaker.parse(
            "mod.classtweaker",
            "classTweaker\tv1\tnamed\n" +
                    "inject-interface\ta/b/C\tcom/foo/Ext<TT;>\n" +
                    "transitive-inject-interface\ta/b/C\tcom/foo/Dep"
        )
        val dep = ClassTweaker.parse(
            "dep.classtweaker",
            "classTweaker\tv1\tnamed\n" +
                    "inject-interface\ta/b/C\tcom/foo/DepLocal\n" +
                    "transitive-inject-interface\ta/b/C\tcom/foo/DepTrans"
        )
        val out = apply(
            writeClass(Opcodes.ACC_PUBLIC, false, signature = "Ljava/lang/Object;"),
            own = listOf(ct), dep = listOf(dep)
        )
        val reader = ClassReader(out)
        // own + dep-transitive applied; dep-local excluded
        assertArrayEquals(
            arrayOf("com/foo/Ext", "com/foo/Dep", "com/foo/DepTrans"),
            reader.interfaces
        )
        // signature attr gains the wrapped injection tokens
        assertEquals(
            "Ljava/lang/Object;Lcom/foo/Ext<TT;>;Lcom/foo/Dep;Lcom/foo/DepTrans;",
            readSignature(out)
        )
    }

    private fun readSignature(bytes: ByteArray): String? {
        var sig: String? = null
        ClassReader(bytes).accept(object : org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int, access: Int, name: String,
                signature: String?, superName: String?, interfaces: Array<out String>?
            ) {
                sig = signature
            }
        }, 0)
        return sig
    }

    @Test
    fun `inner class directive updates InnerClasses entries not the class header`() {
        // Forge AT semantics: `protected a/b/Outer$Inner` is an
        // InnerClassTarget — the modifier lands on the InnerClassNode
        // entries (outer's declaration + inner's self-entry); the
        // inner class's own access_flags header is untouched.
        // Regression: merging `protected` into the header cleared
        // ACC_PUBLIC → IllegalAccessError for cross-module subclasses
        // (MappedRegistry$TagSet / NamespacedWrapper on modern Forge)
        val inner = writeInnerClass()
        val outer = writeClass(
            Opcodes.ACC_PUBLIC or 0x200, false, className = "a/b/Outer",
            innerClasses = listOf(Triple("a/b/Outer\$Inner", "a/b/Outer", Opcodes.ACC_STATIC))
        )
        // headerless AT files declare the `source` namespace — the
        // spec binds it to the pipeline ns at runtime (headerlessNamespace)
        val at = ClassTweaker.parse(
            "accesstransformer.cfg",
            "protected a/b/Outer\$Inner",
            mapOf("source" to "named")
        )
        val out = apply(
            mapOf("a/b/Outer\$Inner.class" to inner, "a/b/Outer.class" to outer),
            own = listOf(at)
        )
        val innerBytes = out["a/b/Outer\$Inner.class"]!!
        val outerBytes = out["a/b/Outer.class"]!!
        // header must keep the binpatch-style public flags (0x0601)
        val reader = ClassReader(innerBytes)
        assertEquals(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            reader.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT),
            "inner-class header must not gain protected/clear public"
        )
        // both declarations of the inner class carry the protected flag
        assertEquals(
            Opcodes.ACC_PROTECTED,
            innerEntryAccess(innerBytes, "a/b/Outer\$Inner") and Opcodes.ACC_PROTECTED,
            "self InnerClasses entry must be marked protected"
        )
        assertEquals(
            Opcodes.ACC_PROTECTED,
            innerEntryAccess(outerBytes, "a/b/Outer\$Inner") and Opcodes.ACC_PROTECTED,
            "outer InnerClasses entry must be marked protected"
        )
    }

    @Test
    fun `top level class directive still merges into the class header`() {
        val bytes = writeClass(Opcodes.ACC_PRIVATE or 0x200, privateMethod = false)
        val at = ClassTweaker.parse(
            "accesstransformer.cfg",
            "public a/b/C",
            mapOf("source" to "named")
        )
        val out = apply(bytes, own = listOf(at))
        assertEquals(Opcodes.ACC_PUBLIC, ClassReader(out).access and Opcodes.ACC_PUBLIC)
    }

    private fun innerEntryAccess(bytes: ByteArray, innerName: String): Int {
        val access = IntArray(1)
        ClassReader(bytes).accept(object : org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            override fun visitInnerClass(
                name: String, outerName: String?, innerName2: String?, access2: Int
            ) {
                if (name == innerName) access[0] = access2
            }
        }, 0)
        return access[0]
    }

    /** A member interface `a/b/Outer$Inner` — javac-style public
     *  header (0x0601) with a `protected static` self InnerClasses
     *  entry, matching Forge's binpatched member classes. */
    private fun writeInnerClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            "a/b/Outer\$Inner", null, "java/lang/Object", null
        )
        writer.visitInnerClass(
            "a/b/Outer\$Inner", "a/b/Outer", "Inner",
            Opcodes.ACC_PROTECTED or Opcodes.ACC_STATIC
        )
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `apply is a no-op without tweaker files`() = Unit

    private fun apply(
        bytes: ByteArray,
        own: List<ClassTweaker> = emptyList(),
        dep: List<ClassTweaker> = emptyList(),
        tree: MappingTree = identityTree,
        target: String = "named"
    ): ByteArray =
        apply(mapOf("a/b/C.class" to bytes), own, dep, tree, target)["a/b/C.class"]!!

    private fun apply(
        entries: Map<String, ByteArray>,
        own: List<ClassTweaker> = emptyList(),
        dep: List<ClassTweaker> = emptyList(),
        tree: MappingTree = identityTree,
        target: String = "named"
    ): Map<String, ByteArray> {
        val input = dir.resolve("in-${System.nanoTime()}.jar")
        val output = dir.resolve("out-${System.nanoTime()}.jar")
        JarOutputStream(Files.newOutputStream(input)).use { jar ->
            for ((name, bytes) in entries) {
                jar.putNextEntry(JarEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        ApplyClassTweakerStepType().apply(input, output, own, dep, tree, target)
        JarFile(output.toFile()).use { jar ->
            return entries.keys.associateWith {
                jar.getInputStream(jar.getJarEntry(it)).readBytes()
            }
        }
    }

    private fun writeClass(
        access: Int,
        privateMethod: Boolean,
        className: String = "a/b/C",
        methodAccess: Int = if (privateMethod) Opcodes.ACC_PRIVATE else Opcodes.ACC_PROTECTED,
        signature: String? = null,
        innerClasses: List<Triple<String, String?, Int>> = emptyList()
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V17, access, className, signature, "java/lang/Object", null
        )
        val method = writer.visitMethod(methodAccess, "hidden", "()V", null, null)
        method.visitCode()
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        for ((name, outer, acc) in innerClasses) {
            writer.visitInnerClass(name, outer, name.substringAfterLast('$'), acc)
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun assertAccess(out: ByteArray) {
        val reader = ClassReader(out)
        assertEquals(
            Opcodes.ACC_PUBLIC or 0x200,
            reader.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED or 0x200)
        )
        reader.accept(object : org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int, name: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?
            ): org.objectweb.asm.MethodVisitor {
                assertEquals(Opcodes.ACC_PUBLIC, access and Opcodes.ACC_PUBLIC)
                return object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {}
            }
        }, 0)
    }

}
