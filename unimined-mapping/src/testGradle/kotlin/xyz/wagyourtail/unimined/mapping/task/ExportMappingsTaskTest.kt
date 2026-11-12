package xyz.wagyourtail.unimined.mapping.task

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ExportMappingsTaskTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile()).build()

    private fun tinyInput(): Path {
        val file = dir.resolve("in.tiny")
        Files.writeString(
            file,
            "tiny\t2\t0\tofficial\tnamed\n" +
                "c\ta/A\tn/A\n" +
                "\tf\tI\tx\ty\n" +
                "\tm\t()V\ta\tb\n"
        )
        return file
    }

    private fun task(): ExportMappingsTask {
        val p = project()
        return p.tasks.register(
            "exportMappings", ExportMappingsTask::class.java
        ).get().apply {
            environmentId.set("main")
            inputBundleFile.set(tinyInput().toFile())
        }
    }

    @Test
    fun `export writes srg rows through the uml writer`() {
        val t = task()
        val out = dir.resolve("out.srg").toFile()
        t.export {
            it.type.set("srg")
            it.location.set(out)
            it.sourceNamespace.set("official")
            it.targetNamespaces.set(listOf("named"))
        }
        t.exportMappings()
        val text = out.readText()
        assertTrue(text.contains("CL: a/A n/A"))
        assertTrue(text.contains("FD: a/A/x n/A/y"))
        assertTrue(text.contains("MD: a/A/a ()V n/A/b ()V"))
    }

    @Test
    fun `export renames emitted namespace columns`() {
        val t = task()
        val out = dir.resolve("out.tiny").toFile()
        t.export {
            it.type.set("tinyv2")
            it.location.set(out)
            it.sourceNamespace.set("official")
            it.targetNamespaces.set(listOf("named"))
            it.renameNs.put("named", "yarn")
        }
        t.exportMappings()
        assertTrue(
            out.readText().startsWith("tiny\t2\t0\tofficial\tyarn"))
    }

    @Test
    fun `export fails fast on unknown format`() {
        val t = task()
        t.export {
            it.type.set("nope")
            it.location.set(dir.resolve("out.nope").toFile())
            it.sourceNamespace.set("official")
            it.targetNamespaces.set(listOf("named"))
        }
        val e = assertThrows(Exception::class.java) { t.exportMappings() }
        assertTrue(e.message!!.contains("nope"))
    }

    @Test
    fun `export validates required fields`() {
        val t = task()
        t.export {
            it.type.set("srg")
            // location/sourceNamespace/targetNamespaces unset
        }
        val e = assertThrows(Exception::class.java) { t.exportMappings() }
        assertTrue(e.message!!.contains("location"))
    }
}
