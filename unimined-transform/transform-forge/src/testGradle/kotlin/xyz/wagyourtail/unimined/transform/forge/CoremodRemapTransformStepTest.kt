package xyz.wagyourtail.unimined.transform.forge

import xyz.wagyourtail.commons.core.logger.Logger
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.mappingTree
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.StepContext
import xyz.wagyourtail.unimined.transform.forge.internal.CoremodRemapTransformStep
import xyz.wagyourtail.unimined.transform.step.TransformStepContext
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue

class CoremodRemapTransformStepTest {

    @TempDir
    lateinit var dir: Path

    private fun tree(): MappingTree = mappingTree("searge", "yarn") {
        clazz("net/minecraft/client/Minecraft", "net/minecraft/class_310") {
            method("func_71410_x;()Lnet/minecraft/client/Minecraft;", "method_1499")
            field("field_71432_P;I", "field_1703")
        }
    }

    private fun mappingsData(tree: MappingTree) = object : MappingData {
        override val viewTypes = setOf(MappingTree::class)
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> view(type: Class<T>): T? =
            if (type == MappingTree::class.java) tree as T else null
        override val properties = emptyMap<String, String>()
    }

    private fun ctx(mappings: MappingData? = null) = object : TransformStepContext {
        override val workDir = dir.resolve("work").also { java.nio.file.Files.createDirectories(it) }
        override val stepContext = object : StepContext {
            override val state: PipelineState by lazy { PipelineState(spec.sourceNamespace, null) }
            override val spec = object : RemapSpec {
                override val sourceNamespace = "searge"
                override val targetNamespace = "yarn"
                override val classpath: List<Path> = emptyList()
            }
            override val mappings = mappings
            override val classpath = emptyList<Path>()
            override val workDir = dir
            override val logger: Logger = uniminedLogger(
                Logging.getLogger(CoremodRemapTransformStepTest::class.java)
            )
        }
        override val inputs = emptyMap<String, List<java.io.File>>()
    }

    private fun jar(name: String, entries: Map<String, ByteArray>): Path {
        val jar = dir.resolve(name)
        jar.parent?.let { java.nio.file.Files.createDirectories(it) }
        ZipOutputStream(jar.outputStream()).use { out ->
            for ((n, bytes) in entries) {
                out.putNextEntry(ZipEntry(n))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `rewrite maps members and class name forms`() {
        val classMap = mapOf(
            "net/minecraft/client/Minecraft" to "net/minecraft/class_310",
            "net.minecraft.client.Minecraft" to "net.minecraft.class_310"
        )
        val memberMap = mapOf(
            "func_71410_x" to "method_1499",
            "field_71432_P" to "field_1703",
            "p_i701_1_" to "name"
        )
        val js = """
            var mc = ASMAPI.mapType("net/minecraft/client/Minecraft");
            var dotted = load("net.minecraft.client.Minecraft");
            call(Lnet/minecraft/client/Minecraft;.func_71410_x)();
            this.field_71432_P = x; this.p_i701_1_ = y;
            var myfunc_71410_x = 1; var fun_1_z = 2; var s = "func_71410_xx";
        """.trimIndent()
        val out = CoremodRemapTransformStep.rewriteCoremodText(js, classMap, memberMap)
        assertTrue(out.contains("net/minecraft/class_310"), "internal path rewritten: $out")
        assertTrue(out.contains("net.minecraft.class_310"), "dotted rewritten: $out")
        assertTrue(out.contains("Lnet/minecraft/class_310;"), "descriptor rewritten: $out")
        assertTrue(out.contains("method_1499"), "method rewritten: $out")
        assertTrue(out.contains("field_1703"), "field rewritten: $out")
        assertTrue(out.contains("name"), "param rewritten: $out")
        // non-token substrings stay: myfunc_/fun_/longer-suffix tokens
        assertTrue(out.contains("myfunc_71410_x"), "embedded name untouched: $out")
        assertTrue(out.contains("fun_1_z"), "non-srg shape untouched: $out")
        assertTrue(out.contains("func_71410_xx"), "overlong suffix untouched: $out")
    }

    @Test
    fun `buildNameMaps collects class and member pairs`() {
        val (classMap, memberMap) = CoremodRemapTransformStep.buildNameMaps(
            tree(), "searge", "yarn"
        )
        assertEquals("net/minecraft/class_310", classMap["net/minecraft/client/Minecraft"])
        assertEquals("net.minecraft.class_310", classMap["net.minecraft.client.Minecraft"])
        assertEquals("method_1499", memberMap["func_71410_x"])
        assertEquals("field_1703", memberMap["field_71432_P"])
    }

    @Test
    fun `transform passes jar without js resources through`() {
        val input = jar("plain.jar", mapOf("a/b/C.class" to byteArrayOf(1, 2)))
        assertSame(input, CoremodRemapTransformStep.transform(input, ctx(mappingsData(tree()))))
    }

    @Test
    fun `transform rewrites js resources with step mappings`() {
        val input = jar("mod.jar", mapOf(
            "coremod/tweak.js" to "hit(func_71410_x, net/minecraft/client/Minecraft)".toByteArray(),
            "a/b/C.class" to byteArrayOf(9)
        ))
        val out = CoremodRemapTransformStep.transform(input, ctx(mappingsData(tree())))
        ZipFile(out.toFile()).use { zip ->
            val js = String(zip.getInputStream(zip.getEntry("coremod/tweak.js")).readBytes())
            assertTrue(js.contains("method_1499"), js)
            assertTrue(js.contains("net/minecraft/class_310"), js)
            assertEquals(9, zip.getInputStream(zip.getEntry("a/b/C.class")).readBytes()[0])
        }
    }

}
