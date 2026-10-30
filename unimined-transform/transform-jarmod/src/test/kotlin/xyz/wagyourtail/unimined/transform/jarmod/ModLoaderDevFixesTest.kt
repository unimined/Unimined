package xyz.wagyourtail.unimined.transform.jarmod

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import xyz.wagyourtail.unimined.transform.jarmod.internal.modloader.ModLoaderDevFixes
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes

/** Synthetic-jar coverage of the classtransform ModLoader fixes. */
class ModLoaderDevFixesTest {

    @TempDir
    lateinit var dir: Path

    /** Minimal ModLoader with the 1.x patch sites. */
    private fun modLoader(fmlMarker: Boolean = false): ByteArray {
        val cn = ClassNode()
        cn.version = Opcodes.V1_8
        cn.access = Opcodes.ACC_PUBLIC
        cn.name = "ModLoader"
        cn.superName = "java/lang/Object"
        if (fmlMarker) cn.fields.add(FieldNode(Opcodes.ACC_STATIC, "fmlMarker", "Z", null, null))

        // init(): File f = new File(ModLoader.class.getProtectionDomain()
        //   .getCodeSource().getLocation().toURI()); readFromClassPath(f);
        val init = MethodNode(Opcodes.ACC_PUBLIC, "init", "()V", null, null)
        val il = init.instructions
        il.add(TypeInsnNode(Opcodes.NEW, "java/io/File"))
        il.add(InsnNode(Opcodes.DUP))
        il.add(LdcInsnNode(org.objectweb.asm.Type.getType("LModLoader;")))
        il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getProtectionDomain", "()Ljava/security/ProtectionDomain;", false))
        il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/security/ProtectionDomain", "getCodeSource", "()Ljava/security/CodeSource;", false))
        il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/security/CodeSource", "getLocation", "()Ljava/net/URL;", false))
        il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/URL", "toURI", "()Ljava/net/URI;", false))
        il.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/net/URI;)V", false))
        il.add(VarInsnNode(Opcodes.ASTORE, 1))
        il.add(VarInsnNode(Opcodes.ALOAD, 1))
        il.add(MethodInsnNode(Opcodes.INVOKESTATIC, "ModLoader", "readFromClassPath", "(Ljava/io/File;)V", false))
        il.add(InsnNode(Opcodes.RETURN))
        cn.methods.add(init)

        // readFromClassPath(File): File[] files = source.listFiles();
        val rfc = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "readFromClassPath", "(Ljava/io/File;)V", null, null)
        rfc.instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
        rfc.instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/File", "listFiles", "()[Ljava/io/File;", false))
        rfc.instructions.add(VarInsnNode(Opcodes.ASTORE, 1))
        rfc.instructions.add(InsnNode(Opcodes.RETURN))
        cn.methods.add(rfc)

        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cn.accept(cw)
        return cw.toByteArray()
    }

    private fun writeJar(path: Path, entries: Map<String, ByteArray>): Path {
        Files.createDirectories(path.parent)
        FileSystems.newFileSystem(path, mapOf("create" to "true")).use { fs ->
            for ((name, bytes) in entries) {
                val p = fs.getPath(name)
                p.parent?.let(Files::createDirectories)
                Files.write(p, bytes)
            }
        }
        return path
    }

    private fun calls(jar: Path, entry: String, method: String): List<MethodInsnNode> {
        FileSystems.newFileSystem(jar).use { fs ->
            val cn = ClassNode()
            ClassReader(fs.getPath(entry).readBytes()).accept(cn, 0)
            return cn.methods.first { it.name == method }
                .instructions.toArray().filterIsInstance<MethodInsnNode>()
        }
    }

    @Test
    fun `uri fix + package fix apply to vanilla ModLoader`() {
        val jar = writeJar(dir.resolve("dev.jar"), mapOf("ModLoader.class" to modLoader()))
        FileSystems.newFileSystem(jar).use(ModLoaderDevFixes::fix)

        val init = calls(jar, "/ModLoader.class", "init")
        assertTrue(init.none { it.owner == "java/net/URL" && it.name == "toURI" },
            "toURI should be redirected away")
        val rfc = calls(jar, "/ModLoader.class", "readFromClassPath")
        // the listFiles calls are rewritten to a copied listAllFiles
        // helper (callable, not inlined — CReplaceCalls semantics)
        val cn = ClassNode()
        FileSystems.newFileSystem(jar).use { fs ->
            ClassReader(fs.getPath("/ModLoader.class").readBytes()).accept(cn, 0)
        }
        assertTrue(cn.methods.any { it.name == "listAllFiles" },
            "listAllFiles helper should be copied onto ModLoader")
        assertTrue(rfc.any { it.name == "listAllFiles" },
            "readFromClassPath should call the helper")
    }

    /** Old ModLoader: read logic inline in <clinit>, no readFromClassPath. */
    private fun oldModLoader(): ByteArray {
        val cn = ClassNode()
        cn.version = Opcodes.V1_8
        cn.access = Opcodes.ACC_PUBLIC
        cn.name = "ModLoader"
        cn.superName = "java/lang/Object"

        // <clinit>: new File((URI)null); astore; readFile(file); return
        val cl = MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        cl.instructions.add(InsnNode(Opcodes.ACONST_NULL)) // placeholder tail marker below
        // build: NEW File; DUP; ACONST_NULL; <init>(URI); ASTORE 0; tail...
        val il = InsnList()
        il.add(TypeInsnNode(Opcodes.NEW, "java/io/File"))
        il.add(InsnNode(Opcodes.DUP))
        il.add(InsnNode(Opcodes.ACONST_NULL))
        il.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/net/URI;)V", false))
        il.add(VarInsnNode(Opcodes.ASTORE, 0))
        // tail: marker call readFile(file) then RETURN
        il.add(VarInsnNode(Opcodes.ALOAD, 0))
        il.add(MethodInsnNode(Opcodes.INVOKESTATIC, "ModLoader", "readFile", "(Ljava/io/File;)V", false))
        il.add(InsnNode(Opcodes.RETURN))
        cl.instructions = il
        cn.methods.add(cl)

        val rf = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "readFile", "(Ljava/io/File;)V", null, null)
        rf.instructions.add(InsnNode(Opcodes.RETURN))
        cn.methods.add(rf)

        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cn.accept(cw)
        return cw.toByteArray()
    }

    @Test
    fun `older clinit ModLoader gets synthesized readFromClassPath`() {
        val jar = writeJar(dir.resolve("old.jar"), mapOf("ModLoader.class" to oldModLoader()))
        FileSystems.newFileSystem(jar).use(ModLoaderDevFixes::fix)
        FileSystems.newFileSystem(jar).use { fs ->
            val cn = ClassNode()
            ClassReader(fs.getPath("/ModLoader.class").readBytes()).accept(cn, 0)
            val rfc = cn.methods.firstOrNull { it.name == "readFromClassPath" }
            assertTrue(rfc != null, "readFromClassPath should be synthesized from clinit tail")
            val clinit = cn.methods.first { it.name == "<clinit>" }
            val insns = clinit.instructions.toArray().filterIsInstance<MethodInsnNode>()
            assertTrue(insns.any { it.owner == "java/lang/System" && it.name == "getProperty" },
                "clinit should iterate java.class.path")
            assertTrue(insns.any { it.name == "readFromClassPath" },
                "clinit should call the synthesized method")
        }
    }

    @Test
    fun `fmlMarker ModLoader is skipped`() {
        val jar = writeJar(dir.resolve("dev.jar"), mapOf("ModLoader.class" to modLoader(fmlMarker = true)))
        FileSystems.newFileSystem(jar).use(ModLoaderDevFixes::fix)
        assertTrue(calls(jar, "/ModLoader.class", "init")
            .any { it.owner == "java/net/URL" && it.name == "toURI" },
            "FML's ModLoader must stay untouched")
    }

    @Test
    fun `ModLoaderHelper presence skips patching`() {
        val jar = writeJar(dir.resolve("dev.jar"), mapOf(
            "ModLoader.class" to modLoader(),
            "cpw/mods/fml/common/modloader/ModLoaderHelper.class" to byteArrayOf()
        ))
        FileSystems.newFileSystem(jar).use(ModLoaderDevFixes::fix)
        assertTrue(calls(jar, "/ModLoader.class", "init")
            .any { it.owner == "java/net/URL" && it.name == "toURI" })
    }

    @Test
    fun `missing ModLoader no-ops`() {
        val jar = writeJar(dir.resolve("dev.jar"), mapOf("x.class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0,0,0,0,0,0,0,0)))
        FileSystems.newFileSystem(jar).use(ModLoaderDevFixes::fix) // no throw
    }
}
