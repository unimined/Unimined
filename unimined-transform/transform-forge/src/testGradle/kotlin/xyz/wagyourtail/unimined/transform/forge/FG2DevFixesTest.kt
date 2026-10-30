package xyz.wagyourtail.unimined.transform.forge

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.FG2DevFixes
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/** Synthetic-jar coverage of the classtransform-driven FG2 fixes. */
class FG2DevFixesTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `coreModManager gets classpath coremod injections`() {
        val jar = dir.resolve("dev.jar")
        val cmm = "cpw/mods/fml/relauncher/CoreModManager"
        writeJar(
            jar, mapOf(
                "$cmm.class" to coreModManager(
                    cmm,
                    "cpw/mods/fml/relauncher/FileListHelper"
                ),
                "cpw/mods/fml/relauncher/FileListHelper.class" to
                        stubClass(
                            "cpw/mods/fml/relauncher/FileListHelper",
                            Triple(
                                "sortFileList", "([Ljava/io/File;)[Ljava/io/File;",
                                Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC
                            )
                        ),
                "cpw/mods/fml/common/launcher/FMLTweaker.class" to
                        stubClass(
                            "cpw/mods/fml/common/launcher/FMLTweaker",
                            Triple(
                                "injectCascadingTweak", "(Ljava/lang/String;)V",
                                Opcodes.ACC_PUBLIC
                            )
                        ),
            )
        )
        FileSystems.newFileSystem(jar).use { FG2DevFixes.fix(it) }
        FileSystems.newFileSystem(jar).use { fs ->
            val node = node(fs.getPath("$cmm.class"))
            // helper spliced
            assertTrue(
                Files.exists(
                    fs.getPath(
                        "xyz/wagyourtail/unimined/transform/forge/internal/fg2/rt/UniminedFg2.class"
                    )
                )
            )
            // sortFileList call site now wrapped by addClasspath
            val method = node.methods.single { it.name == "discoverCoreMods" }
            val insns = method.instructions.toArray().toList()
            val addCp = insns.indexOfFirst {
                it is org.objectweb.asm.tree.MethodInsnNode &&
                        it.owner.endsWith("UniminedFg2") && it.name == "addClasspath"
            }
            val sort = insns.indexOfFirst {
                it is org.objectweb.asm.tree.MethodInsnNode &&
                        it.name == "sortFileList"
            }
            assertTrue(
                addCp in 0 until sort,
                "addClasspath must precede sortFileList"
            )
            // cascadingTweak prologue emitted
            val cct = node.methods.single { it.name == "handleCascadingTweak" }
            assertTrue(cct.instructions.toArray().any {
                it is org.objectweb.asm.tree.MethodInsnNode &&
                        it.name == "isOnClasspath"
            }, "handleCascadingTweak prologue missing")
        }
    }

    @Test
    fun `folderResourcePack gets dirs field + delegates`() {
        val jar = dir.resolve("dev2.jar")
        val pack = "net/minecraftforge/fml/client/FMLFolderResourcePack"
        writeJar(
            jar, mapOf(
                "$pack.class" to folderResourcePack(pack),
                "net/minecraftforge/fml/client/IFolderResourcePack.class" to
                        folderSuper("net/minecraftforge/fml/client/IFolderResourcePack"),
            )
        )
        FileSystems.newFileSystem(jar).use { FG2DevFixes.fix(it) }
        FileSystems.newFileSystem(jar).use { fs ->
            val node = node(fs.getPath("$pack.class"))
            assertNotNull(node.fields.find { it.name == "unimined\$dirs" })
            // domain + hasResource delegates emitted for super names
            assertNotNull(node.methods.find {
                it.name == "getResourceDomains" && it.desc == "()Ljava/util/Set;"
            })
            assertNotNull(node.methods.find {
                it.name == "hasResourceName" && it.desc == "(Ljava/lang/String;)Z"
            })
            // (String)InputStream's super call swapped to the helper
            val ism = node.methods.single {
                it.desc == "(Ljava/lang/String;)Ljava/io/InputStream;"
            }
            assertTrue(ism.instructions.toArray().any {
                it is org.objectweb.asm.tree.MethodInsnNode &&
                        it.owner.endsWith("UniminedFg2") &&
                        it.name == "getInputStreamByName"
            })
        }
    }

    @Test
    fun `languageRegistry rescans mod dirs`() {
        val jar = dir.resolve("dev3.jar")
        val lr = "net/minecraftforge/fml/common/registry/LanguageRegistry"
        writeJar(
            jar, mapOf(
                "$lr.class" to languageRegistry(lr),
                "net/minecraftforge/fml/common/ModContainer.class" to
                        stubClass(
                            "net/minecraftforge/fml/common/ModContainer",
                            Triple(
                                "getSource", "()Ljava/io/File;",
                                Opcodes.ACC_PUBLIC
                            )
                        ),
            )
        )
        FileSystems.newFileSystem(jar).use { FG2DevFixes.fix(it) }
        FileSystems.newFileSystem(jar).use { fs ->
            val node = node(fs.getPath("$lr.class"))
            val method = node.methods.single { it.name == "loadLanguagesFor" }
            val calls = method.instructions.toArray().filter {
                it is org.objectweb.asm.tree.MethodInsnNode &&
                        it.name == "getDirectories"
            }
            assertTrue(
                calls.isNotEmpty(),
                "MOD_CLASSES rescan loop missing"
            )
            assertTrue(method.instructions.toArray().count {
                it is org.objectweb.asm.tree.MethodInsnNode &&
                        it.name == "searchDirForLanguages"
            } > 1, "injected searchDirForLanguages call missing")
        }
    }

    private fun languageRegistry(self: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V17, Opcodes.ACC_PUBLIC, self, null,
            "java/lang/Object", null
        )
        cw.visitMethod(
            Opcodes.ACC_PUBLIC, "searchDirForLanguages",
            "(Ljava/io/File;Ljava/lang/String;Lnet/minecraftforge/fml/relauncher/Side;)V",
            null, null
        ).apply {
            visitCode()
            insn(Opcodes.RETURN)
            visitMaxs(0, 4)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC, "loadLanguagesFor",
            "(Lnet/minecraftforge/fml/common/ModContainer;Lnet/minecraftforge/fml/relauncher/Side;)V",
            null, null
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraftforge/fml/common/ModContainer", "getSource",
                "()Ljava/io/File;", false
            )
            visitLdcInsn("")
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, self,
                "searchDirForLanguages",
                "(Ljava/io/File;Ljava/lang/String;Lnet/minecraftforge/fml/relauncher/Side;)V",
                false
            )
            insn(Opcodes.RETURN)
            visitMaxs(4, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    // ---- synthetic class emitters ------------------------------------

    private fun node(path: Path) = ClassNode().also {
        Files.newInputStream(path).use { s -> ClassReader(s).accept(it, 0) }
    }

    private fun writeJar(jar: Path, entries: Map<String, ByteArray>) {
        FileSystems.newFileSystem(
            jar,
            mapOf("create" to "true")
        ).use { fs ->
            entries.forEach { (name, bytes) ->
                val p = fs.getPath(name)
                p.parent?.let(Files::createDirectories)
                Files.write(p, bytes)
            }
        }
    }

    private fun stubClass(
        name: String,
        vararg methods: Triple<String, String, Int>
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V17, Opcodes.ACC_PUBLIC, name, null,
            "java/lang/Object", null
        )
        for ((mname, mdesc, access) in methods) {
            cw.visitMethod(access, mname, mdesc, null, null).apply {
                visitCode()
                visitInsn(Opcodes.ACONST_NULL)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun MethodVisitor.insn(op: Int) = visitInsn(op)

    private fun coreModManager(self: String, fileListHelper: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V17, Opcodes.ACC_PUBLIC, self, null,
            "java/lang/Object", null
        )
        cw.visitField(
            Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, "tweaker",
            "Lcpw/mods/fml/common/launcher/FMLTweaker;", null, null
        )
        cw.visitField(
            Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, "tweakSorting",
            "Ljava/util/Map;", null, null
        )
        // static File[] discoverCoreMods() { return FileListHelper.sortFileList(files); }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC,
            "discoverCoreMods", "()[Ljava/io/File;", null, null
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(
                Opcodes.INVOKESTATIC, fileListHelper,
                "sortFileList", "([Ljava/io/File;)[Ljava/io/File;", false
            )
            insn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        // static void handleCascadingTweak(File f, String a, String b, List l, Object o)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC,
            "handleCascadingTweak",
            "(Ljava/io/File;Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Ljava/lang/Object;)V",
            null, null
        ).apply {
            visitCode()
            insn(Opcodes.RETURN)
            visitMaxs(0, 5)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun folderResourcePack(self: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V17, Opcodes.ACC_PUBLIC, self, null,
            "net/minecraftforge/fml/client/IFolderResourcePack", null
        )
        // <init>(ModContainer) — arg type is opaque; descriptor only needs a File-returning call pattern
        cw.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>",
            "(Lnet/minecraftforge/fml/common/ModContainer;)V", null, null
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "net/minecraftforge/fml/client/IFolderResourcePack",
                "<init>", "()V", false
            )
            insn(Opcodes.RETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC, "getInputStreamByName",
            "(Ljava/lang/String;)Ljava/io/InputStream;", null, null
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "net/minecraftforge/fml/client/IFolderResourcePack",
                "getInputStreamByName", "(Ljava/lang/String;)Ljava/io/InputStream;", false
            )
            insn(Opcodes.ARETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun folderSuper(self: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V17, Opcodes.ACC_PUBLIC, self, null,
            "java/lang/Object", null
        )
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object",
                "<init>", "()V", false
            )
            insn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC, "getResourceDomains",
            "()Ljava/util/Set;", null, null
        ).apply {
            visitCode()
            visitInsn(Opcodes.ACONST_NULL)
            insn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC, "hasResourceName",
            "(Ljava/lang/String;)Z", null, null
        ).apply {
            visitCode()
            insn(Opcodes.ICONST_0)
            insn(Opcodes.IRETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }
}
