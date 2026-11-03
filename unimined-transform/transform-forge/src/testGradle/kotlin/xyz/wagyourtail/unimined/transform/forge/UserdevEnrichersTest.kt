package xyz.wagyourtail.unimined.transform.forge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.transform.forge.internal.UserdevConfig
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevEnrichers
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.readText

class UserdevEnrichersTest {

    @TempDir
    lateinit var tmp: java.io.File

    private fun cfg(data: String) = UserdevConfig(
        Json.parseToJsonElement("""{"data": {$data}}""") as JsonObject
    )

    @Test
    fun `dataPath resolves strings and side-aware objects`() {
        val c = cfg(
            """"access": "data/accesstransformer.cfg",
            "interfaces": {"client": "data/client_interfaces.cfg"}"""
        )
        assertEquals(
            "data/accesstransformer.cfg",
            UserdevEnrichers.dataPath(c, "access", "server")
        )
        assertEquals(
            "data/client_interfaces.cfg",
            UserdevEnrichers.dataPath(c, "interfaces", "joined")
        )
        // {client}-only entries fall back to client on any side —
        // mirrors 1.x's joined→client convention
        assertEquals(
            "data/client_interfaces.cfg",
            UserdevEnrichers.dataPath(c, "interfaces", "server")
        )
        assertNull(UserdevEnrichers.dataPath(c, "enum", "client"))
    }

    @Test
    fun `classTweakerFiles generates CT directives`() {
        val jar = tmp.toPath().resolve("userdev.jar")
        JarOutputStream(jar.toFile().outputStream()).use { j ->
            j.putNextEntry(JarEntry("data/injected_interfaces.cfg"))
            j.write("net/minecraft/Foo net/minecraft/InterfaceA net/minecraft/InterfaceB\n".toByteArray())
            j.closeEntry()
            j.putNextEntry(JarEntry("data/enum_extensions.cfg"))
            j.write("net/minecraft/Enum FOO_EXTENSION\n".toByteArray())
            j.closeEntry()
        }
        val out = tmp.toPath().resolve("enrichers")
        val extracted = UserdevEnrichers.extract(
            jar, mapOf(
                "interfaces" to "data/injected_interfaces.cfg",
                "enum" to "data/enum_extensions.cfg"
            ), out
        )
        val cts = UserdevEnrichers.classTweakerFiles(extracted, "searge", out)
        assertEquals(2, cts.size)
        val ifaceCt = cts.first {
            it.fileName.toString() == "userdev_injected_interfaces.cfg"
        }
        assertEquals(
            "classTweaker v1 searge\n" +
                    "inject-interface net/minecraft/Foo net/minecraft/InterfaceA\n" +
                    "inject-interface net/minecraft/Foo net/minecraft/InterfaceB\n",
            ifaceCt.readText()
        )
        assertEquals(
            "classTweaker v2 searge\n" +
                    "extend-enum net/minecraft/Enum FOO_EXTENSION\n",
            cts.first {
                it.fileName.toString() == "userdev_enum_extensions.cfg"
            }.readText()
        )
    }
}
