package xyz.wagyourtail.unimined.test.integration

import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.util.openZipFileSystem
import xyz.wagyourtail.unimined.util.runTestProject
import java.nio.file.Files
import kotlin.io.path.inputStream
import kotlin.test.*

class FabricInterfaceInjectionTest {
    @ParameterizedTest
    @MethodSource("xyz.wagyourtail.unimined.util.IntegrationTestUtils#versions")
    fun test_fabric_interface_injection(gradleVersion: String) {
        val projectName = "Fabric-Interface-Injection"
        try {
            val result = runTestProject(projectName, gradleVersion)

            try {
                result.task(":build")?.outcome?.let {
                    if (it != TaskOutcome.SUCCESS) throw Exception("build failed")
                } ?: throw Exception("build failed")
            } catch (e: Exception) {
                println(result.output)
                throw Exception(e)
            }
        } catch (e: UnexpectedBuildFailure) {
            println(e)
            throw Exception("build failed", e)
        }

        val fs = openZipFileSystem(projectName, ".gradle/unimined/local/fabric/fabric/minecraft-1.14.4-fabric-merged+fixed+ii-mojmap.jar")

        assertNotNull(fs, "Couldn't find the interface injected jar!")

        fs.use {
            val target = it.getPath("/net/minecraft/advancements/Advancement.class")

            assertNotNull(target, "Couldn't find the injected class in mc jar!")
            assertTrue(Files.exists(target), "Couldn't find the injected class in mc jar!")

            val reader = ClassReader(target.inputStream())
            val node = ClassNode()
            reader.accept(node, 0)

            assertNotNull(node.interfaces, "Injected class doesn't have any interface!")
            assertEquals(1, node.interfaces.size)
            assertContains(node.interfaces, "com/example/fabric/ExampleInterface")
        }

        fs.close()
    }
}
