package xyz.wagyourtail.unimined.runs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.runs.internal.LaunchScriptFiles
import java.nio.file.Path

/** Output-shape tests for the `.sh`/`.cmd` launch-script renderer. */
class LaunchScriptFilesTest {

    @TempDir
    lateinit var dir: Path

    private val launch = LaunchScriptFiles.Launch(
        javaExecutable = "/usr/bin/java",
        mainClass = "net.minecraft.client.main.Main",
        classpath = listOf("/m2/mc.jar", "/m2/lib with space.jar"),
        jvmArgs = listOf("-Xmx2G", "-Dflag=1"),
        programArgs = listOf("--username", "Dev"),
        environment = mapOf("MC_ENV" to "dev"),
    )

    @Test
    fun `write emits executable sh and cmd`() {
        val files = LaunchScriptFiles.write(dir.toFile(), "runClient", launch)
        assertEquals(2, files.size)

        val sh = dir.resolve("runClient.sh").toFile()
        assertTrue(sh.isFile)
        assertTrue(sh.canExecute(), ".sh should be marked executable")
        val shText = sh.readText()
        assertTrue(shText.startsWith("#!/bin/sh"))
        assertTrue(shText.contains("cd \"\$(dirname \"\$0\")\""))
        assertTrue(shText.contains("export MC_ENV=\"dev\""))
        assertTrue(shText.contains(
            "-cp \"/m2/mc.jar:/m2/lib with space.jar\""))
        assertTrue(shText.contains(
            "exec \"/usr/bin/java\" \"-Xmx2G\" \"-Dflag=1\""))
        assertTrue(shText.contains(
            "\"net.minecraft.client.main.Main\" \"--username\" \"Dev\""))

        val cmd = dir.resolve("runClient.cmd").toFile()
        assertTrue(cmd.isFile)
        val cmdText = cmd.readText()
        assertTrue(cmdText.contains("cd /d \"%~dp0\""))
        assertTrue(cmdText.contains("set \"MC_ENV=dev\""))
        // windows classpath separator
        assertTrue(cmdText.contains(
            "-cp \"/m2/mc.jar;/m2/lib with space.jar\""))
        assertTrue(cmdText.contains(
            "\"net.minecraft.client.main.Main\" \"--username\" \"Dev\""))
    }

    @Test
    fun `sh quoting escapes dollar backslash and quotes`() {
        val text = LaunchScriptFiles.renderSh(launch.copy(
            jvmArgs = listOf("a b", "x\$y", "q\"w", "c:\\path")))
        assertTrue(text.contains("\"a b\""))
        assertTrue(text.contains("\"x\\\$y\""))
        assertTrue(text.contains("\"q\\\"w\""))
        assertTrue(text.contains("\"c:\\\\path\""))
    }
}
