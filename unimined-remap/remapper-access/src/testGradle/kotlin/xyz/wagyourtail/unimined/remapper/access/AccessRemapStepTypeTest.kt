package xyz.wagyourtail.unimined.remapper.access

import xyz.wagyourtail.unimined.remap.spi.PipelineState
import org.gradle.api.GradleException
import xyz.wagyourtail.commons.core.logger.Logger
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.NAMED_NAMESPACES_KEY
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.mappingTree
import xyz.wagyourtail.unimined.remap.AccessRemapStepSpec
import xyz.wagyourtail.unimined.remap.RemapStepRegistry
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class AccessRemapStepTypeTest {

    @TempDir
    lateinit var dir: Path

    private val spec = object : RemapSpec {
        override val sourceNamespace = "intermediary"
        override val targetNamespace = "named"
        override val classpath: List<Path> = emptyList()
    }

    private val tree = mappingTree("intermediary", "named") {
        clazz("example/Target", "mapped/Target") {
            method("run;()V", "execute")
            field("value;I", "count")
        }
        clazz("example/Marker", "mapped/Marker")
    }

    private val mappings: MappingData = UmlMappingData(tree)

    private fun ctx(): StepContext = object : StepContext {
        override val surface = RemapSurface.JAR
     override val state: PipelineState by lazy { PipelineState(spec.sourceNamespace, null) }
        override val spec = this@AccessRemapStepTypeTest.spec
        override val mappings = this@AccessRemapStepTypeTest.mappings
        override val classpath = emptyList<Path>()
        override val workDir = dir
        override val logger: Logger = xyz.wagyourtail.unimined.api.util.uniminedLogger(Logging.getLogger(AccessRemapStepTypeTest::class.java))
    }

    private fun inputJar(): Path {
        val jar = dir.resolve("in.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            fun put(name: String, text: String) {
                out.putNextEntry(JarEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
            put(
                "test.accesswidener", """
                accessWidener v1 intermediary
                accessible class example/Target
                accessible method example/Target run ()V
                accessible field example/Target value I
            """.trimIndent()
            )
            put(
                "META-INF/accesstransformer.cfg", """
                public example.Target
                protected example.Target run()V
            """.trimIndent()
            )
            put(
                "custom.ct", """
                classTweaker v2 intermediary
                accessible class example/Target
                mutable field example/Target value I
                inject-interface example/Target example/Marker<TT;>
            """.trimIndent()
            )
            put("resource.txt", "unchanged")
        }
        return jar
    }

    @Test
    fun `registry discovers access remapper via ServiceLoader`() {
        val type = RemapStepRegistry().resolve(
            AccessRemapStepSpec(), RemapSurface.JAR, spec
        )
        assertEquals("access", type.name)
        assertEquals("remap-access", type.stepName)
    }

    @Test
    fun `rewrites embedded AW AT and CT resources`() {
        val ctx = ctx()
        val out = AccessRemapStepType().create(AccessRemapStepSpec(), ctx)
            .apply(inputJar(), ctx)

        JarFile(out.toFile()).use { jar ->
            val aw = jar.entryText("test.accesswidener").replace(Regex("\\s+"), " ")
            assertTrue(aw.contains("accessWidener v1 named"))
            assertTrue(aw.contains("accessible class mapped/Target"))
            assertTrue(aw.contains("accessible method mapped/Target execute ()V"))
            assertTrue(aw.contains("accessible field mapped/Target count I"))

            val at = jar.entryText("META-INF/accesstransformer.cfg")
            assertTrue(at.contains("public mapped.Target"))
            assertTrue(at.contains("protected mapped.Target execute()V"))

            val ct = jar.entryText("custom.ct").replace(Regex("\\s+"), " ")
            assertTrue(ct.contains("classTweaker v2 named"))
            assertTrue(ct.contains("accessible class mapped/Target"))
            assertTrue(ct.contains("mutable field mapped/Target count I"))
            assertTrue(ct.contains("inject-interface mapped/Target mapped/Marker<TT;>"))
            assertEquals("unchanged", jar.entryText("resource.txt"))
        }
    }

    @Test
    fun `explicit paths and format filter control processing`() {
        val jar = dir.resolve("explicit.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            fun put(name: String, text: String) {
                out.putNextEntry(JarEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
            put("loader-declared.txt", "public example.Target\n")
            put(
                "test.accesswidener", """
                accessWidener v1 intermediary
                accessible class example/Target
            """.trimIndent()
            )
        }

        val stepSpec = AccessRemapStepSpec().apply {
            formats = setOf("at")
            paths = setOf("loader-declared.txt")
        }
        val ctx = ctx()
        val out = AccessRemapStepType().create(stepSpec, ctx).apply(jar, ctx)

        JarFile(out.toFile()).use { result ->
            assertEquals("public mapped.Target", result.entryText("loader-declared.txt").trimEnd())
            assertTrue(result.entryText("test.accesswidener").contains("example/Target"))
        }
    }

    @Test
    fun `FMLAT manifest paths are treated as AT resources`() {
        val jar = dir.resolve("fmlat.jar")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("FMLAT", "META-INF/custom-at.txt")
        }
        JarOutputStream(Files.newOutputStream(jar), manifest).use { out ->
            fun put(name: String, text: String) {
                out.putNextEntry(JarEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
            put("META-INF/custom-at.txt", "public example.Target\n")
        }

        val ctx = ctx()
        val out = AccessRemapStepType().create(AccessRemapStepSpec(), ctx).apply(jar, ctx)

        JarFile(out.toFile()).use { result ->
            assertEquals("public mapped.Target", result.entryText("META-INF/custom-at.txt").trimEnd())
        }
    }

    @Test
    fun `rebases injected_interfaces keys inside mod json`() {
        val jar = dir.resolve("modjson.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            fun put(name: String, text: String) {
                out.putNextEntry(JarEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
            put(
                "fabric.mod.json", """
                {
                  "id": "test",
                  "custom": {
                    "loom:injected_interfaces": {
                      "example/Target": ["mod/IfaceA", "mod/IfaceB"]
                    }
                  }
                }
            """.trimIndent()
            )
            put("resource.txt", "unchanged")
        }

        val ctx = ctx()
        val out = AccessRemapStepType().create(AccessRemapStepSpec(), ctx)
            .apply(jar, ctx)

        JarFile(out.toFile()).use { result ->
            val fmj = result.entryText("fabric.mod.json")
            // target keys rebase to the destination namespace; the
            // mod-owned interface values are never remapped
            assertTrue(fmj.contains("mapped/Target"))
            assertTrue(fmj.contains("mod/IfaceA"))
            assertFalse(fmj.contains("example/Target"))
            assertEquals("unchanged", result.entryText("resource.txt"))
        }
    }

    @Test
    fun `named header resolves through the dev-namespace alias`() {
        // dev column named `mojmap`, dev-shaped dep headers declare the
        // canonical `named` — devNamespaceAliases maps it to the real
        // column (raw `mojmap` matches the tree directly, no alias needed)
        val devSpec = object : RemapSpec {
            override val sourceNamespace = "intermediary"
            override val targetNamespace = "mojmap"
            override val classpath: List<Path> = emptyList()
        }
        val devTree = mappingTree("intermediary", "mojmap") {
            clazz("example/Target", "dev/Target") {
                method("run;()V", "launch")
            }
        }
        val devCtx = object : StepContext {
            override val surface = RemapSurface.JAR
            override val state: PipelineState by lazy {
                PipelineState(devSpec.sourceNamespace, null)
            }
            override val spec = devSpec
            override val mappings: MappingData = object : MappingData {
                override val viewTypes = emptySet<kotlin.reflect.KClass<*>>()
                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> view(type: Class<T>): T? =
                    devTree.takeIf { type.isInstance(it) } as? T
                override val properties =
                    mapOf(NAMED_NAMESPACES_KEY to "mojmap")
            }
            override val classpath = emptyList<Path>()
            override val workDir = dir
            override val logger: Logger =
                xyz.wagyourtail.unimined.api.util.uniminedLogger(
                    Logging.getLogger(AccessRemapStepTypeTest::class.java))
        }
        val jar = dir.resolve("named-header.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("test.accesswidener"))
            out.write(
                ("accessWidener\tv1\tnamed\n" +
                        "accessible\tclass\tdev/Target\n" +
                        "accessible\tmethod\tdev/Target\tlaunch\t()V\n")
                    .toByteArray()
            )
            out.closeEntry()
        }
        val stepSpec = AccessRemapStepSpec().apply {
            devNamespaceAliases = setOf("named")
            devNamespaceLabel = "named"
        }
        val out = AccessRemapStepType().create(stepSpec, devCtx)
            .apply(jar, devCtx)
        JarFile(out.toFile()).use { result ->
            val aw = result.entryText("test.accesswidener")
                .replace(Regex("\\s+"), " ")
            assertTrue(aw.contains("accessWidener v1 named"), aw)
            assertTrue(aw.contains("accessible class dev/Target"), aw)
            assertTrue(
                aw.contains("accessible method dev/Target launch ()V"), aw)
        }
    }

    @Test
    fun `unknown format fails clearly`() {
        val spec = AccessRemapStepSpec().apply { formats = setOf("unknown") }
        val e = assertThrows(GradleException::class.java) {
            AccessRemapStepType().create(spec, ctx()).apply(inputJar(), ctx())
        }
        assertTrue(e.message!!.contains("unknown"))
    }

    private fun JarFile.entryText(name: String): String =
        getInputStream(getEntry(name)).bufferedReader().use { it.readText() }

}
