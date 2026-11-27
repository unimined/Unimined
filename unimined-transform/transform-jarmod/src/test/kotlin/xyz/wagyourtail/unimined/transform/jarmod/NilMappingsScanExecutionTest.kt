package xyz.wagyourtail.unimined.transform.jarmod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import xyz.wagyourtail.unimined.mapping.mappingTree
import xyz.wagyourtail.unimined.api.mapping.BundleMappingData
import xyz.wagyourtail.unimined.api.mapping.MappingBundle
import xyz.wagyourtail.unimined.api.mapping.SerializedMappingData
import xyz.wagyourtail.unimined.remapper.uml.nil.NilMappingsScanTask
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/** Executes the scan task against synthetic bytecode and asserts the artifact rows. */
class NilMappingsScanExecutionTest {

    @TempDir
    lateinit var temp: Path

    private fun classBytes(name: String, access: Int = Opcodes.ACC_PUBLIC, block: ClassNode.() -> Unit = {}): ByteArray {
        val node = ClassNode().apply {
            this.access = access
            this.name = name
            this.superName = "java/lang/Object"
            this.version = Opcodes.V1_8
            block()
        }
        val writer = ClassWriter(0)
        node.accept(writer)
        return writer.toByteArray()
    }

    private fun writeJar(path: Path, entries: Map<String, ByteArray>) {
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            entries.forEach { (name, bytes) ->
                jar.putNextEntry(ZipEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
    }

    @Test
    fun `scan emits target mappings and pristine access widening rows`() {
        val projectDir = temp.resolve("project").toFile().apply { mkdirs() }
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val work = temp.resolve("work").apply { Files.createDirectories(this) }

        val devJar = work.resolve("dev-game.jar")
        writeJar(devJar, mapOf(
            "game/Target.class" to classBytes("game/Target") {
                methods.add(MethodNode(Opcodes.ACC_PUBLIC, "target", "()V", null, null).apply {
                    visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 1); visitEnd()
                })
            }
        ))
        val officialJar = work.resolve("official-game.jar")
        writeJar(officialJar, mapOf(
            "a.class" to classBytes("a") {
                methods.add(MethodNode(Opcodes.ACC_PRIVATE, "m", "()V", null, null).apply {
                    visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 1); visitEnd()
                })
            }
        ))

        val modDir = work.resolve("mod-classes")
        Files.createDirectories(modDir.resolve("testmod"))
        Files.write(modDir.resolve("testmod/UsesGame.class"), classBytes("testmod/UsesGame") {
            methods.add(MethodNode(Opcodes.ACC_PUBLIC, "run", "()V", null, null).apply {
                visitCode()
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "game/Target", "target", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 1)
                visitEnd()
            })
        })

        val tree = mappingTree("official", "named") {
            clazz("a", "game/Target") {
                method("m;()V", "target;()V")
            }
        }
        val bundleFile = work.resolve("bundle.json")
        SerializedMappingData.write(BundleMappingData(MappingBundle.capture("official", "named", tree)), bundleFile)
        val outputFile = work.resolve("mappings.json")

        val scan = project.tasks.register("scanSyntheticNilMappings", NilMappingsScanTask::class.java) { task ->
            task.classDirs.from(modDir)
            task.scanClasspath.from(devJar)
            task.accessTruthJars.from(officialJar)
            task.devNamespace.set("named")
            task.defaultBundle.set(project.layout.file(project.provider { bundleFile.toFile() }))
            task.targetNamespaces.put("default", "official")
            task.outputFile.set(project.layout.file(project.provider { outputFile.toFile() }))
        }.get()

        scan.scan()
        val json = Json.parseToJsonElement(outputFile.toFile().readText()).jsonObject
        val default = json.getValue("default").jsonObject
        val target = default.getValue("classes").jsonArray
            .map { it.jsonObject }
            .first { it.getValue("from").jsonPrimitive.content == "game/Target" }
        assertEquals("a", target.getValue("to").jsonPrimitive.content)
        assertEquals("m()V", target.getValue("methods").jsonObject
            .getValue("target()V").jsonPrimitive.content)
        val widenMethods = default.getValue("widen").jsonObject
            .getValue("methods").jsonArray.map { it.jsonObject }
        assertTrue(widenMethods.any {
            it.getValue("owner").jsonPrimitive.content == "a" &&
                it.getValue("sig").jsonPrimitive.content == "m()V"
        }, "private official method must be widened: $json")
    }
}
