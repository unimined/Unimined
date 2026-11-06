package xyz.wagyourtail.unimined.mod.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

class ModMetadataScanTest {

    /** Emit a minimal class: [ifaces] as implemented interfaces, optional
     *  `@Mixin` annotation carrying [targets] as `value` class literals. */
    private fun classBytes(
        internalName: String,
        ifaces: List<String> = emptyList(),
        mixinTargets: List<String> = emptyList(),
        superName: String = "java/lang/Object"
    ): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null,
            superName, ifaces.toTypedArray()
        )
        if (mixinTargets.isNotEmpty()) {
            val av = cw.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/Mixin;", true
            )
            val arr = av.visitArray("value")
            mixinTargets.forEach {
                arr.visit(null, Type.getObjectType(it))
            }
            arr.visitEnd()
            av.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun writeClass(root: Path, internalName: String, bytes: ByteArray) {
        val f = root.resolve("$internalName.class")
        f.parent.createDirectories()
        f.writeBytes(bytes)
    }

    @Test
    fun `interfaceInjections maps mixin targets to implemented interfaces`() {
        val dir = Files.createTempDirectory("scan")
        writeClass(
            dir, "com/example/MyMixin",
            classBytes(
                "com/example/MyMixin",
                ifaces = listOf("com/example/ExampleInterface"),
                mixinTargets = listOf("net/minecraft/class_161")
            )
        )
        val out = ModMetadataScan.interfaceInjections(
            listOf("com.example.MyMixin"), listOf(dir.toFile()), emptySet()
        )
        assertEquals(
            mapOf("net/minecraft/class_161" to listOf("com/example/ExampleInterface")),
            out
        )
    }

    @Test
    fun `interfaceInjections skips classes without Mixin annotation`() {
        val dir = Files.createTempDirectory("scan")
        writeClass(
            dir, "com/example/Plain",
            classBytes(
                "com/example/Plain",
                ifaces = listOf("com/example/ExampleInterface")
            )
        )
        val out = ModMetadataScan.interfaceInjections(
            listOf("com.example.Plain"), listOf(dir.toFile()), emptySet()
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `interfaceInjections dedupes against existing rules`() {
        val dir = Files.createTempDirectory("scan")
        writeClass(
            dir, "com/example/MyMixin",
            classBytes(
                "com/example/MyMixin",
                ifaces = listOf(
                    "com/example/ExampleInterface",
                    "com/example/OtherInterface"
                ),
                mixinTargets = listOf("net/minecraft/class_161")
            )
        )
        val out = ModMetadataScan.interfaceInjections(
            listOf("com.example.MyMixin"), listOf(dir.toFile()),
            existingRules = setOf(
                "net/minecraft/class_161" to "com/example/ExampleInterface"
            )
        )
        assertEquals(
            mapOf("net/minecraft/class_161" to listOf("com/example/OtherInterface")),
            out
        )
    }

    @Test
    fun `gametestClasses finds direct implementors and subclasses`() {
        val dir = Files.createTempDirectory("scan")
        val iface = "net/fabricmc/fabric/api/gametest/v1/FabricGameTest"
        writeClass(
            dir, "com/example/BaseTest",
            classBytes("com/example/BaseTest", ifaces = listOf(iface))
        )
        writeClass(
            dir, "com/example/ConcreteTest",
            classBytes("com/example/ConcreteTest", superName = "com/example/BaseTest")
        )
        writeClass(
            dir, "com/example/NotATest",
            classBytes("com/example/NotATest")
        )
        val out = ModMetadataScan.gametestClasses(
            listOf(dir.toFile()), iface
        )
        assertEquals(
            listOf("com.example.BaseTest", "com.example.ConcreteTest"), out
        )
    }

    @Test
    fun `parseMixinConfig reads package and member arrays`() {
        val cfg = ModMetadataScan.parseMixinConfig(
            """{"package":"com.example.mixins","mixins":["Foo","Bar"],
               "client":["ClientOnly"],"refmap":"x-refmap.json"}"""
        )!!
        assertEquals("com.example.mixins", cfg.packageName)
        assertEquals(
            listOf(
                "com.example.mixins.Foo", "com.example.mixins.Bar",
                "com.example.mixins.ClientOnly"
            ), cfg.classes
        )
    }

    @Test
    fun `discoverMixinConfigs finds mixin jsons and skips generated`() {
        val dir = Files.createTempDirectory("res")
        val mixinsDir = dir.resolve("com/example/mixins")
        mixinsDir.createDirectories()
        Files.writeString(
            mixinsDir.resolve("manual.mixins.json"),
            """{"package":"com.example.mixins","mixins":["Manual"]}"""
        )
        Files.writeString(dir.resolve("generated.mixins.json"), """{}""")
        val found = ModMetadataScan.discoverMixinConfigs(
            listOf(dir.toFile()), exclude = setOf("generated.mixins.json")
        )
        assertEquals(1, found.size)
        assertEquals("com/example/mixins/manual.mixins.json", found[0].first)
        assertEquals(
            listOf("com.example.mixins.Manual"), found[0].second.classes
        )
    }

    @Test
    fun `existingInterfaceRules parses transitive and plain directives`() {
        val dir = Files.createTempDirectory("access")
        val ct = dir.resolve("mod.classTweaker")
        Files.writeString(
            ct,
            "classTweaker\tv1\tnamed\n" +
                    "inject-interface\ta/b/C\tcom/foo/I1\n" +
                    "transitive-inject-interface\ta/b/C\tcom/foo/I2\n"
        )
        val rules = ModMetadataScan.existingInterfaceRules(listOf(ct.toFile()))
        assertEquals(
            setOf(
                "a/b/C" to "com/foo/I1",
                "a/b/C" to "com/foo/I2"
            ), rules
        )
    }

}
