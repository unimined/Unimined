package xyz.wagyourtail.unimined.remapper.uml

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodNode
import xyz.wagyourtail.unimined.api.mapping.MappingComposeRequest
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
import xyz.wagyourtail.unimined.api.mapping.MappingBundle
import xyz.wagyourtail.unimined.mapping.DefaultMappingComposer
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.api.spec.ClasspathScanAlias
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.formats.tiny.v2.TinyV2Reader
import xyz.wagyourtail.unimined.remap.ModArtifactsStepSpec
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Dependency-artifact regression coverage for the two mod-remap side
 * channels that ordinary type remapping cannot see: mixin annotation/refmap
 * bridging and LaunchWrapper coremod descriptor literals. Synthetic jars and
 * mapping trees only — no fixture project and no repository resolution.
 */
class DependencyArtifactChannelTest {

    @TempDir
    lateinit var dir: Path

    private class Harness(
        override val sourceNamespace: String,
        override val targetNamespace: String,
        val mappings: MappingData,
        val work: Path,
        override val scanAliases: List<ClasspathScanAlias> = emptyList(),
    ) : RemapSpec {
        override val classpath: List<Path> = emptyList()

        fun ctx(): StepContext = object : StepContext {
            override val surface = RemapSurface.JAR
            override val state: PipelineState by lazy {
                PipelineState(sourceNamespace, null)
            }
            override val spec = this@Harness
            override val mappings = this@Harness.mappings
            override val classpath = emptyList<Path>()
            override val workDir = work
            override val logger = xyz.wagyourtail.unimined.api.util.uniminedLogger(
                Logging.getLogger(DependencyArtifactChannelTest::class.java)
            )
        }
    }

    private fun tinyData(treeText: String): MappingData = UmlMappingData(runBlocking {
        TinyV2Reader.read(treeText)
    })

    /** Tiny rows use leading tabs for member entries; spell that indentation
     *  as spaces in source so patches stay whitespace-clean, then restore the
     *  wire-format tab after margin stripping. */
    private fun String.tinyDocument(): String =
        trimIndent().replace(Regex("^    ", RegexOption.MULTILINE), "\t")

    /**
     * Compose per-channel tiny documents through the same composer the
     * environment mapping resolver uses. The legacy-fabric failure shape is
     * composition-sensitive: legacy intermediary and calamus are sibling
     * hops off `official`, with feather chained from calamus, so a single
     * already-joined four-column document is not an equivalent probe.
     */
    private fun composeTinyDocuments(vararg treeTexts: String): MappingData {
        val ctx = object : MappingContext {
            override val environmentId = "dependency-artifact-channel-test"
            override val logger = xyz.wagyourtail.unimined.api.util.uniminedLogger(
                Logging.getLogger(DependencyArtifactChannelTest::class.java)
            )
            override val cacheDir: Path? = null
            override fun resolveMaven(notation: String): Path =
                throw UnsupportedOperationException("no maven resolution in this test")
        }
        return DefaultMappingComposer().compose(
            MappingComposeRequest(treeTexts.map(::tinyData)), ctx
        )
    }

    private fun writeJar(path: Path, entries: Map<String, ByteArray>) {
        ZipOutputStream(Files.newOutputStream(path)).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    private fun classBytes(name: String, block: ClassNode.() -> Unit = {}): ByteArray {
        val node = ClassNode().apply {
            version = Opcodes.V1_8
            access = Opcodes.ACC_PUBLIC
            this.name = name
            superName = "java/lang/Object"
            block()
        }
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return writer.toByteArray()
    }

    private fun readClass(path: Path, name: String): ClassNode {
        ZipFile(path.toFile()).use { zip ->
            val entry = zip.getEntry("$name.class")
            assertNotNull(entry, "missing $name.class in $path")
            return ClassNode().also {
                ClassReader(zip.getInputStream(entry).readBytes()).accept(it, 0)
            }
        }
    }

    private fun remapAndPostProcess(
        harness: Harness,
        input: Path,
        modArtifacts: ModArtifactsStepSpec,
        tag: String,
    ): Path {
        val ctx = harness.ctx()
        val remapped = UmlRemapStepType().create(RemapStepSpec(), ctx)
            .apply(input, ctx)
        // Give every invocation its own output-looking input name so temp
        // products cannot collide inside the shared test workdir.
        val renamed = dir.resolve("$tag-${remapped.fileName}")
        Files.copy(remapped, renamed, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return UmlModArtifactsStepType().create(modArtifacts, ctx)
            .apply(renamed, ctx)
    }

    // ---------- LiteLoader-style shipped-refmap channel ----------

    private val liteMixin = "com/mumfrey/liteloader/common/mixin/MixinMinecraftServer"
    private val liteCompileRef = "updateTimeLightAndEntities()V"
    private val liteTargetRef =
        "Lnet/minecraft/server/MinecraftServer;updateTimeLightAndEntities()V"

    private fun liteHarness() = Harness(
        "searge", "mcp", tinyData("""
        tiny	2	0	searge	mcp	official
        c	net/minecraft/server/MinecraftServer	net/minecraft/server/MinecraftServer	net/minecraft/server/MinecraftServer
            m	()V	func_71190_q	updateTimeLightAndEntities	D
        """.tinyDocument()), dir
    )

    private fun liteMixinBytes(): ByteArray = classBytes(liteMixin) {
        visibleAnnotations = listOf(AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;").apply {
            values = listOf("value", listOf(Type.getObjectType("net/minecraft/server/MinecraftServer")))
        })
        methods.add(MethodNode(
            Opcodes.ACC_PUBLIC,
            "onServerTick",
            "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V",
            null,
            null,
        ).apply {
            visibleAnnotations = listOf(AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;").apply {
                values = listOf(
                    "method", listOf(liteCompileRef),
                    "at", listOf(AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;").apply {
                        values = listOf("value", "HEAD")
                    }),
                )
            })
            instructions.add(InsnNode(Opcodes.RETURN))
        })
    }

    private fun liteInputJar(tag: String): Path {
        val refmap = """{
          "mappings": {
            "$liteMixin": {
              "$liteCompileRef": "Lnet/minecraft/server/MinecraftServer;D()V"
            }
          },
          "data": {
            "notch": {
              "$liteMixin": {
                "$liteCompileRef": "Lnet/minecraft/server/MinecraftServer;D()V"
              }
            },
            "searge": {
              "$liteMixin": {
                "$liteCompileRef": "Lnet/minecraft/server/MinecraftServer;func_71190_q()V"
              }
            }
          }
        }"""
        val jar = dir.resolve("liteloader-like-$tag.jar")
        writeJar(jar, mapOf(
            "$liteMixin.class" to liteMixinBytes(),
            "mixins.liteloader.core.json" to """{
              "required": true,
              "package": "com.mumfrey.liteloader.common.mixin",
              "refmap": "mixins.liteloader.core.refmap.json",
              "mixins": ["MixinMinecraftServer"]
            }""".encodeToByteArray(),
            "mixins.liteloader.core.refmap.json" to refmap.encodeToByteArray(),
        ))
        return jar
    }

    private fun injectMethodValues(path: Path): List<Any?> {
        val node = readClass(path, liteMixin)
        val method = node.methods.first { it.name == "onServerTick" }
        val inject = method.visibleAnnotations.first {
            it.desc == "Lorg/spongepowered/asm/mixin/injection/Inject;"
        }
        val idx = inject.values.indexOf("method")
        @Suppress("UNCHECKED_CAST")
        return inject.values[idx + 1] as List<Any?>
    }

    private fun remapLite(emitRefmap: Boolean, tag: String): Path {
        val harness = liteHarness()
        return remapAndPostProcess(
            harness,
            liteInputJar(tag),
            ModArtifactsStepSpec().apply { this.emitRefmap = emitRefmap },
            tag,
        )
    }

    @Test
    fun `liteloader-style dependency resolves source refmap data instead of production notch rows`() {
        val out = remapLite(emitRefmap = false, tag = "liteloader-noemit")
        assertEquals(listOf(liteTargetRef), injectMethodValues(out),
            "the data.searge bridge must resolve through searge→mcp; the production notch row must not win")
        ZipFile(out.toFile()).use { zip ->
            val refmap = zip.getInputStream(zip.getEntry("mixins.liteloader.core.refmap.json"))
                .readBytes().decodeToString()
            assertEquals("{\"mappings\":{}}", refmap,
                "emitRefmap=false intentionally neutralizes the runtime refmap after final in-place rewrite")
            val config = zip.getInputStream(zip.getEntry("mixins.liteloader.core.json"))
                .readBytes().decodeToString()
            assertFalse(config.contains("\"refmap\""),
                "emitRefmap=false must strip the config refmap pointer")
        }
    }

    @Test
    fun `liteloader-style dependency emits target-space refmap values when emission is enabled`() {
        val out = remapLite(emitRefmap = true, tag = "liteloader-emit")
        assertEquals(listOf(liteTargetRef), injectMethodValues(out))
        ZipFile(out.toFile()).use { zip ->
            val json = Json.parseToJsonElement(
                zip.getInputStream(zip.getEntry("mixins.liteloader.core.refmap.json"))
                    .readBytes().decodeToString()
            ).jsonObject
            val entries = json.getValue("mappings").jsonObject
                .getValue(liteMixin).jsonObject
            assertEquals(liteTargetRef,
                entries.getValue(liteCompileRef).jsonPrimitive.content,
                "runtime substitution must produce the same mcp-space selector, never the notch D row")
        }
    }

    // ---------- Legacy Fabric inherited-member refmap channel ----------

    private val lfMixin = "net/legacyfabric/fabric/mixin/event/lifecycle/client/ClientWorldMixin"

    /**
     * legacy-fabric-api's lifecycle mixin shape: annotation keys are compile
     * names (`onEntitySpawned`), the shipped refmap points at the
     * intermediary owner (`class_478;method_3626`), but the member is really
     * inherited from `World` (`class_1150`) rather than declared on
     * `ClientWorld` (`class_478`). The scan-alias inheritance graph is the
     * only thing that can resolve that indirection.
     */
    @Test
    fun `legacyfabric refmap resolves inherited client world member through scan alias`() {
        // Production shape: legacyfabric's intermediary hop and ornithe's
        // calamus hop are sibling branches off official; feather then chains
        // from calamus. The composer must stitch rows across all three
        // documents — a direct four-column document does not exercise that.
        val legacyIntermediaryDoc = """
        tiny	2	0	official	legacyIntermediary
        c	amu	net/minecraft/class_1150
            m	(Lvg;)V	b	method_3626
            m	(Lvg;)V	c	method_3663
        c	bsb	net/minecraft/class_478
        c	vg	net/minecraft/class_864
        """.tinyDocument()
        val calamusDoc = """
        tiny	2	0	official	calamusGen2
        c	amu	net/minecraft/unmapped/C_64041439
            m	(Lvg;)V	b	m_15771998
            m	(Lvg;)V	c	m_93270146
        c	bsb	net/minecraft/unmapped/C_34399650
        c	vg	net/minecraft/unmapped/C_42232651
        """.tinyDocument()
        val featherDoc = """
        tiny	2	0	calamusGen2	featherGen2
        c	net/minecraft/unmapped/C_64041439	net/minecraft/world/World
            m	(Lnet/minecraft/unmapped/C_42232651;)V	m_15771998	notifyEntityAdded
            m	(Lnet/minecraft/unmapped/C_42232651;)V	m_93270146	notifyEntityRemoved
        c	net/minecraft/unmapped/C_34399650	net/minecraft/client/world/ClientWorld
        c	net/minecraft/unmapped/C_42232651	net/minecraft/entity/Entity
        """.tinyDocument()
        val composed = composeTinyDocuments(
            legacyIntermediaryDoc, calamusDoc, featherDoc
        )

        fun officialClass(name: String, superName: String = "java/lang/Object", methods: List<Pair<String, String>> = emptyList()) =
            classBytes(name) {
                this.superName = superName
                methods.forEach { (methodName, desc) ->
                    this.methods.add(MethodNode(Opcodes.ACC_PUBLIC, methodName, desc, null, null).apply {
                        instructions.add(InsnNode(Opcodes.RETURN))
                    })
                }
            }
        val mcJar = dir.resolve("lf-official-mc.jar")
        writeJar(mcJar, mapOf(
            "vg.class" to officialClass("vg"),
            "amu.class" to officialClass("amu", methods = listOf(
                "b" to "(Lvg;)V",
                "c" to "(Lvg;)V",
            )),
            "bsb.class" to officialClass("bsb", "amu"),
        ))

        val probe = Harness("legacyIntermediary", "featherGen2", composed, dir)
        val aliasMappings = dir.resolve("lf-scan-alias.bundle")
        Files.write(
            aliasMappings,
            MappingBundle.capture(
                "official", "legacyIntermediary",
                probe.mappings.view(MappingTree::class.java)!!
            ).toJson().encodeToByteArray()
        )
        val harness = Harness(
            "legacyIntermediary", "featherGen2", composed, dir,
            scanAliases = listOf(ClasspathScanAlias(mcJar, "official", aliasMappings))
        )

        val mixinBytes = classBytes(lfMixin) {
            visibleAnnotations = listOf(AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;").apply {
                values = listOf("value", listOf(Type.getObjectType("net/minecraft/class_478")))
            })
            methods.add(MethodNode(
                Opcodes.ACC_PUBLIC,
                "unloadEntity",
                "(Lnet/minecraft/class_864;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V",
                null,
                null,
            ).apply {
                visibleAnnotations = listOf(AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;").apply {
                    values = listOf(
                        "method", listOf("onEntitySpawned"),
                        "at", listOf(AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;").apply {
                            values = listOf("value", "TAIL")
                        }),
                    )
                })
                instructions.add(InsnNode(Opcodes.RETURN))
            })
        }
        val refmap = """{
          "mappings": {
            "$lfMixin": {
              "onEntitySpawned": "Lnet/minecraft/class_478;method_3626(Lnet/minecraft/class_864;)V"
            }
          },
          "data": {
            "named:intermediary": {
              "$lfMixin": {
                "onEntitySpawned": "Lnet/minecraft/class_478;method_3626(Lnet/minecraft/class_864;)V"
              }
            }
          }
        }"""
        val input = dir.resolve("lf-lifecycle.jar")
        writeJar(input, mapOf(
            "$lfMixin.class" to mixinBytes,
            "legacy-fabric-lifecycle-events-v1-common.mixins.json" to """{
              "required": true,
              "package": "net.legacyfabric.fabric.mixin.event.lifecycle",
              "client": ["client.ClientWorldMixin"],
              "refmap": "legacy-fabric-lifecycle-events-v1-common-refmap.json"
            }""".encodeToByteArray(),
            "legacy-fabric-lifecycle-events-v1-common-refmap.json" to refmap.encodeToByteArray(),
        ))

        val out = remapAndPostProcess(
            harness, input,
            ModArtifactsStepSpec().apply { emitRefmap = false },
            "lf-lifecycle"
        )
        val node = readClass(out, lfMixin)
        val method = node.methods.first { it.name == "unloadEntity" }
        val inject = method.visibleAnnotations.first {
            it.desc == "Lorg/spongepowered/asm/mixin/injection/Inject;"
        }
        val methodValues = inject.values[inject.values.indexOf("method") + 1] as List<*>
        assertEquals(
            listOf("Lnet/minecraft/client/world/ClientWorld;notifyEntityAdded(Lnet/minecraft/entity/Entity;)V"),
            methodValues,
            "the intermediary refmap row must resolve through ClientWorld's inherited World member " +
                "and emit UML's canonical owner-qualified method spec"
        )
    }

    // ---------- LaunchWrapper coremod string-descriptor channel ----------

    private val oldCrashDesc =
        "(Lnet/minecraft/crash/CrashReport;Lnet/minecraft/crash/CrashReportCategory;)V"
    private val newCrashDesc =
        "(Lnet/minecraft/util/crash/CrashReport;Lnet/minecraft/util/crash/CrashReportCategory;)V"
    private val transformerName = "io/example/compat/asm/EnhanceCrashReportsTransformer"

    private fun crashHarness() = Harness(
        "searge", "featherGen2", tinyData("""
        tiny	2	0	searge	featherGen2
        c	net/minecraft/crash/CrashReport	net/minecraft/util/crash/CrashReport
        c	net/minecraft/crash/CrashReportCategory	net/minecraft/util/crash/CrashReportCategory
        """.tinyDocument()), dir
    )

    private fun transformerJar(tag: String): Path {
        val bytes = classBytes(transformerName) {
            interfaces = listOf("net/minecraft/launchwrapper/IClassTransformer")
            methods.add(MethodNode(
                Opcodes.ACC_PUBLIC,
                "transform",
                "(Ljava/lang/String;[B)[B",
                null,
                null,
            ).apply {
                instructions.add(LdcInsnNode(oldCrashDesc))
                instructions.add(org.objectweb.asm.tree.InsnNode(Opcodes.ACONST_NULL))
                instructions.add(org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN))
            })
        }
        val jar = dir.resolve("unimixins-like-$tag.jar")
        writeJar(jar, mapOf("$transformerName.class" to bytes))
        return jar
    }

    private fun ldcConstants(path: Path): List<Any?> {
        val node = readClass(path, transformerName)
        return node.methods.flatMap { method ->
            method.instructions.toArray().filterIsInstance<LdcInsnNode>().map { it.cst }
        }
    }

    @Test
    fun `launchwrapper transformer descriptor literals remap only when the loader-scoped channel is enabled`() {
        val harness = crashHarness()
        val offOut = remapAndPostProcess(
            harness,
            transformerJar("ldc-off"),
            ModArtifactsStepSpec(),
            "ldc-off",
        )
        assertTrue(oldCrashDesc in ldcConstants(offOut),
            "the unsafe universal string pass stays off by default")

        val onOut = remapAndPostProcess(
            harness,
            transformerJar("ldc-on"),
            ModArtifactsStepSpec().apply { enableLaunchwrapperTransformerLdcRemap() },
            "ldc-on",
        )
        val constants = ldcConstants(onOut)
        assertTrue(newCrashDesc in constants,
            "IClassTransformer bytecode-construction descriptors must reach featherGen2")
        assertFalse(oldCrashDesc in constants,
            "the source-namespace descriptor must not survive in the transformer")
    }
}
