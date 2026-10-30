package xyz.wagyourtail.unimined.transform.jarmod

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import xyz.wagyourtail.unimined.transform.jarmod.internal.modloader.ModLoaderDevFixes
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import org.junit.jupiter.api.Assertions.assertTrue

/** env-gated smoke test over real ModLoader artifacts in /tmp */
class RealModLoaderSmokeTest {

    private fun calls(jar: URI, method: String): List<MethodInsnNode> {
        FileSystems.newFileSystem(jar, emptyMap<String, Any>()).use { fs ->
            val p = fs.getPath("/ModLoader.class")
            val cn = ClassNode()
            ClassReader(p.readBytes()).accept(cn, 0)
            val m = cn.methods.firstOrNull { it.name == method } ?: return emptyList()
            return m.instructions.toArray().filterIsInstance<MethodInsnNode>()
        }
    }

    @Test
    fun `real ModLoader jars fix end-to-end`() {
        for (v in listOf("1.2.5", "1.3.2", "1.4.7", "1.5.2")) {
            val jar = Paths.get("/tmp/modloader-$v.jar")
            assumeTrue(jar.exists(), "missing $jar")
            FileSystems.newFileSystem(jar).use(ModLoaderDevFixes::fix)
            FileSystems.newFileSystem(jar).use { fs ->
                val cn = ClassNode()
                ClassReader(fs.getPath("/ModLoader.class").readBytes()).accept(cn, 0)
                val names = cn.methods.map { it.name }
                assertTrue("listAllFiles" in names, "$v: helper missing")
                val rfc = cn.methods.first { it.name == "readFromClassPath" }
                val calls = rfc.instructions.toArray().filterIsInstance<MethodInsnNode>()
                assertTrue(calls.any { it.name == "listAllFiles" }, "$v: listFiles not redirected")
                assertTrue(calls.any { it.name == "relPath" }, "$v: getName not redirected")
                assertTrue(calls.any { it.name == "isModClass" }, "$v: startsWith not redirected")
            }
        }
    }
}
