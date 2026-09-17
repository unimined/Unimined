package xyz.wagyourtail.unimined.internal.minecraft.transform.fixes

import org.objectweb.asm.*
import org.objectweb.asm.tree.ClassNode
import java.nio.file.FileSystem
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.writeBytes

object FixFG2ResourceLoading {

    val folderResourcePack = listOf(
        "cpw/mods/fml/client/FMLFolderResourcePack.class",
        "net/minecraftforge/fml/client/FMLFolderResourcePack.class",
    )

    val languageRegistry = listOf(
        "cpw/mods/fml/common/registry/LanguageRegistry.class",
        "net/minecraftforge/fml/common/registry/LanguageRegistry.class",
    )

    fun fixResourceLoading(fs: FileSystem) {

        for (file in folderResourcePack) {
            val path = fs.getPath(file)
            if (path.exists()) {

                val modContainerIntl = if (file.startsWith("cpw")) {
                    "cpw/mods/fml/common/ModContainer"
                } else {
                    "net/minecraftforge/fml/common/ModContainer"
                }

                val loadControllerIntl = if (file.startsWith("cpw")) {
                    "cpw/mods/fml/common/LoadController"
                } else {
                    "net/minecraftforge/fml/common/LoadController"
                }

                val self = file.substring(0, file.length - 6)

                val reader = path.inputStream().use { ClassReader(it) }

                // grab the super type to find the name of the `()Ljava/util/Set;` method
                val superPath = reader.superName + ".class"
                val superReader = fs.getPath(superPath).inputStream().use { ClassReader(it) }
                val superNode = ClassNode()
                superReader.accept(superNode, ClassReader.SKIP_CODE)
                val domains = superNode.methods.find { it.desc == "()Ljava/util/Set;" }!!
                val hasResourceName = superNode.methods.find { it.desc == "(Ljava/lang/String;)Z" }!!

                var foundHasResource = false

                val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
                reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {

                    override fun visit(
                        version: Int,
                        access: Int,
                        name: String?,
                        signature: String?,
                        superName: String?,
                        interfaces: Array<out String>?
                    ) {
                        super.visit(version, access, name, signature, superName, interfaces)
                        visitField(Opcodes.ACC_PRIVATE, "unimined\$dirs", "[Ljava/io/File;", null, null).visitEnd()
                    }

                    override fun visitMethod(
                        access: Int,
                        mname: String?,
                        descriptor: String?,
                        signature: String?,
                        exceptions: Array<out String>?
                    ): MethodVisitor {
                        if (mname == "<init>") {
                            return object : MethodVisitor(api, super.visitMethod(access, mname, descriptor, signature, exceptions)) {
                                override fun visitInsn(opcode: Int) {
                                    if (opcode != Opcodes.RETURN) {
                                        super.visitInsn(opcode)
                                        return
                                    }
                                    // unimined$dirs = unimined$getDirectories(modContainer.getSource())
                                    super.visitVarInsn(Opcodes.ALOAD, 0)
                                    super.visitVarInsn(Opcodes.ALOAD, 0)
                                    super.visitVarInsn(Opcodes.ALOAD, 1)
                                    visitMethodInsn(
                                        Opcodes.INVOKEINTERFACE,
                                        modContainerIntl,
                                        "getSource",
                                        "()Ljava/io/File;",
                                        true
                                    )
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, loadControllerIntl, "unimined\$getDirectories", "(Ljava/io/File;)[Ljava/io/File;", false)
                                    super.visitFieldInsn(Opcodes.PUTFIELD, self, "unimined\$dirs", "[Ljava/io/File;")
                                    super.visitInsn(opcode)
                                }
                            }
                        } else if (descriptor.equals("(Ljava/lang/String;)Ljava/io/InputStream;")) {
                            // replace super call with unimined$getInputStreamByName(name)
                            return object : MethodVisitor(api, super.visitMethod(access, mname, descriptor, signature, exceptions)) {
                                override fun visitMethodInsn(
                                    opcode: Int,
                                    owner: String?,
                                    name: String?,
                                    descriptor: String?,
                                    isInterface: Boolean
                                ) {
                                    if (name == mname && opcode == Opcodes.INVOKESPECIAL) {
                                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, "unimined\$getInputStreamByName", "(Ljava/lang/String;)Ljava/io/InputStream;", false)
                                    } else {
                                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                                    }
                                }
                            }
                        } else if (descriptor.equals("(Ljava/lang/String;)Z")) {
                            foundHasResource = true
                            // replace super call with unimined$hasResource(name)
                            return object : MethodVisitor(api, super.visitMethod(access, mname, descriptor, signature, exceptions)) {
                                override fun visitMethodInsn(
                                    opcode: Int,
                                    owner: String?,
                                    name: String?,
                                    descriptor: String?,
                                    isInterface: Boolean
                                ) {
                                    if (name == mname && opcode == Opcodes.INVOKESPECIAL) {
                                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, "unimined\$hasResource", "(Ljava/lang/String;)Z", false)
                                    } else {
                                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                                    }
                                }
                            }
                        }

                        return super.visitMethod(access, mname, descriptor, signature, exceptions)
                    }

                    override fun visitEnd() {
                        visitMethod(Opcodes.ACC_PUBLIC, domains.name, domains.desc, null, null).apply {
                            // call unimined$getResourceDomains()
                            visitCode()
                            visitVarInsn(Opcodes.ALOAD, 0)
                            visitMethodInsn(
                                Opcodes.INVOKEVIRTUAL,
                                self,
                                "unimined\$getResourceDomains",
                                "()Ljava/util/Set;",
                                false
                            )
                            visitInsn(Opcodes.ARETURN)
                            visitMaxs(0, 0)
                            visitEnd()
                        }

                        if (!foundHasResource) {
                            visitMethod(Opcodes.ACC_PUBLIC, hasResourceName.name, "(Ljava/lang/String;)Z", null, null).apply {
                                visitCode()
                                visitVarInsn(Opcodes.ALOAD, 0)
                                visitVarInsn(Opcodes.ALOAD, 1)
                                visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, "unimined\$hasResource", "(Ljava/lang/String;)Z", false)
                                visitInsn(Opcodes.IRETURN)
                                visitMaxs(0, 0)
                                visitEnd()
                            }
                        }

                        visitMethod(Opcodes.ACC_PRIVATE, "unimined\$getResourceDomains", "()Ljava/util/Set;", null, null).apply {
                            visitCode()
                            // HashSet var1 = new HashSet()
                            visitTypeInsn(Opcodes.NEW, "java/util/HashSet")
                            visitInsn(Opcodes.DUP)
                            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "()V", false)
                            visitVarInsn(Opcodes.ASTORE, 1)
                            // for (int var2 = 0; var2 < unimined$dirs.length; ++var2) {
                            visitInsn(Opcodes.ICONST_0)
                            visitVarInsn(Opcodes.ISTORE, 2)
                            val l1 = Label()
                            visitLabel(l1)
                            visitVarInsn(Opcodes.ILOAD, 2)
                            visitVarInsn(Opcodes.ALOAD, 0)
                            visitFieldInsn(Opcodes.GETFIELD, self, "unimined\$dirs", "[Ljava/io/File;")
                            visitInsn(Opcodes.ARRAYLENGTH)
                            val l2 = Label()
                            visitJumpInsn(Opcodes.IF_ICMPGE, l2)
                            //   val var3 = new File(unimined$dirs[var2], "assets/");
                            visitTypeInsn(Opcodes.NEW, "java/io/File")
                            visitInsn(Opcodes.DUP)
                            visitVarInsn(Opcodes.ALOAD, 0)
                            visitFieldInsn(Opcodes.GETFIELD, self, "unimined\$dirs", "[Ljava/io/File;")
                            visitVarInsn(Opcodes.ILOAD, 2)
                            visitInsn(Opcodes.AALOAD)
                            visitLdcInsn("assets/")
                            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/io/File;Ljava/lang/String;)V", false)
                            visitVarInsn(Opcodes.ASTORE, 3)
                            //   if (var3.isDirectory()) {
                            visitVarInsn(Opcodes.ALOAD, 3)
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "isDirectory", "()Z", false)
                            val l3 = Label()
                            visitJumpInsn(Opcodes.IFEQ, l3)
                            //     var var4 = var3.listFiles(DirectoryFileFilter.DIRECTORY);
                            visitVarInsn(Opcodes.ALOAD, 3)
                            visitFieldInsn(Opcodes.GETSTATIC, "org/apache/commons/io/filefilter/DirectoryFileFilter", "DIRECTORY", "Lorg/apache/commons/io/filefilter/IOFileFilter;")
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "listFiles", "(Ljava/io/FileFilter;)[Ljava/io/File;", false)
                            visitVarInsn(Opcodes.ASTORE, 4)
                            //     int var5 = var3.length
                            visitVarInsn(Opcodes.ALOAD, 4)
                            visitInsn(Opcodes.ARRAYLENGTH)
                            visitVarInsn(Opcodes.ISTORE, 5)
                            //     for (int var6 = 0; var6 < var5; ++var6) {
                            visitInsn(Opcodes.ICONST_0)
                            visitVarInsn(Opcodes.ISTORE, 6)
                            val l4 = Label()
                            visitLabel(l4)
                            visitVarInsn(Opcodes.ILOAD, 6)
                            visitVarInsn(Opcodes.ILOAD, 5)
                            val l5 = Label()
                            visitJumpInsn(Opcodes.IF_ICMPGE, l5)
                            //       val var7 = var4[var6];
                            visitVarInsn(Opcodes.ALOAD, 4)
                            visitVarInsn(Opcodes.ILOAD, 6)
                            visitInsn(Opcodes.AALOAD)
                            visitVarInsn(Opcodes.ASTORE, 7)
                            //       val var8 = var3.toURI().relativize(var7.toURI()).getPath();
                            visitVarInsn(Opcodes.ALOAD, 3)
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "toURI", "()Ljava/net/URI;", false)
                            visitVarInsn(Opcodes.ALOAD, 7)
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "toURI", "()Ljava/net/URI;", false)
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/URI", "relativize", "(Ljava/net/URI;)Ljava/net/URI;", false)
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/URI", "getPath", "()Ljava/lang/String;", false)
                            visitVarInsn(Opcodes.ASTORE, 8)
                            //       if (var8.equals(var8.toLowerCase())) {
                            visitVarInsn(Opcodes.ALOAD, 8)
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toLowerCase", "()Ljava/lang/String;", false)
                            visitVarInsn(Opcodes.ALOAD, 8)
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
                            val l6 = Label()
                            visitJumpInsn(Opcodes.IFEQ, l6)
                            //         var1.add(var8.substring(0, var8.length() - 1));
                            visitVarInsn(Opcodes.ALOAD, 1)
                            visitVarInsn(Opcodes.ALOAD, 8)
                            visitInsn(Opcodes.ICONST_0)
                            visitVarInsn(Opcodes.ALOAD, 8)
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)
                            visitInsn(Opcodes.ICONST_1)
                            visitInsn(Opcodes.ISUB)
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false)
                            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true)
                            visitInsn(Opcodes.POP)
                            //       }
                            visitLabel(l6)
                            //     }
                            visitIincInsn(6, 1)
                            visitJumpInsn(Opcodes.GOTO, l4)
                            visitLabel(l5)
                            //   }
                            visitLabel(l3)
                            visitIincInsn(2, 1)
                            visitJumpInsn(Opcodes.GOTO, l1)
                            // }
                            visitLabel(l2)
                            // return var1;
                            visitVarInsn(Opcodes.ALOAD, 1)
                            visitInsn(Opcodes.ARETURN)

                            visitMaxs(0, 0)
                            visitEnd()
                        }

                        visitMethod(Opcodes.ACC_PRIVATE, "unimined\$hasResource", "(Ljava/lang/String;)Z", null, null).apply {
                            visitCode()
                            // for (int var2 = 0; var2 < unimined$dirs.length; ++var2) {
                            visitInsn(Opcodes.ICONST_0)
                            visitVarInsn(Opcodes.ISTORE, 2)
                            val l1 = Label()
                            visitLabel(l1)
                            visitVarInsn(Opcodes.ILOAD, 2)
                            visitVarInsn(Opcodes.ALOAD, 0)
                            visitFieldInsn(Opcodes.GETFIELD, self, "unimined\$dirs", "[Ljava/io/File;")
                            visitInsn(Opcodes.ARRAYLENGTH)
                            val l2 = Label()
                            visitJumpInsn(Opcodes.IF_ICMPGE, l2)
                            // val var3 = new File(unimined$dirs[var2], var1);
                            visitTypeInsn(Opcodes.NEW, "java/io/File")
                            visitInsn(Opcodes.DUP)
                            visitVarInsn(Opcodes.ALOAD, 0)
                            visitFieldInsn(Opcodes.GETFIELD, self, "unimined\$dirs", "[Ljava/io/File;")
                            visitVarInsn(Opcodes.ILOAD, 2)
                            visitInsn(Opcodes.AALOAD)
                            visitVarInsn(Opcodes.ALOAD, 1)
                            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/io/File;Ljava/lang/String;)V", false)
                            visitVarInsn(Opcodes.ASTORE, 3)
                            // if (var3.isFile()) return true;
                            visitVarInsn(Opcodes.ALOAD, 3)
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "isFile", "()Z", false)
                            val l3 = Label()
                            visitJumpInsn(Opcodes.IFEQ, l3)
                            visitInsn(Opcodes.ICONST_1)
                            visitInsn(Opcodes.IRETURN)
                            // var2++
                            visitLabel(l3)
                            visitIincInsn(2, 1)
                            visitJumpInsn(Opcodes.GOTO, l1)
                            visitLabel(l2)
                            // return false;
                            visitInsn(Opcodes.ICONST_0)
                            visitInsn(Opcodes.IRETURN)
                            visitMaxs(0, 0)
                            visitEnd()
                        }

                        visitMethod(Opcodes.ACC_PRIVATE, "unimined\$getInputStreamByName", "(Ljava/lang/String;)Ljava/io/InputStream;", null, null).apply {
                            visitCode()
                            // for (int var2 = 0; var2 < unimined$dirs.length; ++var2) {
                            visitInsn(Opcodes.ICONST_0)
                            visitVarInsn(Opcodes.ISTORE, 2)
                            val l1 = Label()
                            visitLabel(l1)
                            visitVarInsn(Opcodes.ILOAD, 2)
                            visitVarInsn(Opcodes.ALOAD, 0)
                            visitFieldInsn(Opcodes.GETFIELD, self, "unimined\$dirs", "[Ljava/io/File;")
                            visitInsn(Opcodes.ARRAYLENGTH)
                            val l2 = Label()
                            visitJumpInsn(Opcodes.IF_ICMPGE, l2)
                            // val var3 = unimined$dirs[var2]
                            visitVarInsn(Opcodes.ALOAD, 0)
                            visitFieldInsn(Opcodes.GETFIELD, self, "unimined\$dirs", "[Ljava/io/File;")
                            visitVarInsn(Opcodes.ILOAD, 2)
                            visitInsn(Opcodes.AALOAD)
                            visitVarInsn(Opcodes.ASTORE, 3)
                            // val var4 = new File(var3, var1)
                            visitTypeInsn(Opcodes.NEW, "java/io/File")
                            visitInsn(Opcodes.DUP)
                            visitVarInsn(Opcodes.ALOAD, 3)
                            visitVarInsn(Opcodes.ALOAD, 1)
                            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/io/File;Ljava/lang/String;)V", false)
                            visitVarInsn(Opcodes.ASTORE, 4)
                            // if (var4.exists()) {
                            visitVarInsn(Opcodes.ALOAD, 4)
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "exists", "()Z", false)
                            val l3 = Label()
                            visitJumpInsn(Opcodes.IFEQ, l3)
                            // return new BufferedInputStream(new FileInputStream(var4))
                            visitTypeInsn(Opcodes.NEW, "java/io/BufferedInputStream")
                            visitInsn(Opcodes.DUP)
                            visitTypeInsn(Opcodes.NEW, "java/io/FileInputStream")
                            visitInsn(Opcodes.DUP)
                            visitVarInsn(Opcodes.ALOAD, 4)
                            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/FileInputStream", "<init>", "(Ljava/io/File;)V", false)
                            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/BufferedInputStream", "<init>", "(Ljava/io/InputStream;)V", false)
                            visitInsn(Opcodes.ARETURN)
                            // }
                            visitLabel(l3)
                            visitIincInsn(2, 1)
                            visitJumpInsn(Opcodes.GOTO, l1)
                            visitLabel(l2)
                            // throw new FileNotFoundException(var1)
                            visitTypeInsn(Opcodes.NEW, "java/io/FileNotFoundException")
                            visitInsn(Opcodes.DUP)
                            visitVarInsn(Opcodes.ALOAD, 1)
                            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/FileNotFoundException", "<init>", "(Ljava/lang/String;)V", false)
                            visitInsn(Opcodes.ATHROW)
                            visitMaxs(0, 0)
                            visitEnd()
                        }

                        super.visitEnd()
                    }
                }, 0)
                path.writeBytes(writer.toByteArray())
            }
        }

        for (file in languageRegistry) {
            val path = fs.getPath(file)
            if (path.exists()) {

                val modContainerIntl = if (file.startsWith("cpw")) {
                    "cpw/mods/fml/common/ModContainer"
                } else {
                    "net/minecraftforge/fml/common/ModContainer"
                }

                val side = if (file.startsWith("cpw")) {
                    "cpw/mods/fml/relauncher/Side"
                } else {
                    "net/minecraftforge/fml/relauncher/Side"
                }

                val loadControllerIntl = if (file.startsWith("cpw")) {
                    "cpw/mods/fml/common/LoadController"
                } else {
                    "net/minecraftforge/fml/common/LoadController"
                }

                val self = file.substring(0, file.length - 6)

                val reader = path.inputStream().use { ClassReader(it) }
                val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
                reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
                    override fun visitMethod(
                        access: Int,
                        mname: String?,
                        descriptor: String?,
                        signature: String?,
                        exceptions: Array<out String>?
                    ): MethodVisitor {
                        if (mname == "loadLanguagesFor") {
                            return object : MethodVisitor(api, super.visitMethod(access, mname, descriptor, signature, exceptions)) {

                                override fun visitMethodInsn(
                                    opcode: Int,
                                    owner: String?,
                                    name: String?,
                                    descriptor: String?,
                                    isInterface: Boolean
                                ) {
                                    if (name == "searchDirForLanguages") {
                                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                                        // unimined$addLanguages(var1, var2)
                                        super.visitVarInsn(Opcodes.ALOAD, 0)
                                        super.visitVarInsn(Opcodes.ALOAD, 1)
                                        super.visitVarInsn(Opcodes.ALOAD, 2)
                                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, "unimined\$addLanguages", "(L$modContainerIntl;L$side;)V", false)
                                    } else super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                                }

                            }
                        }
                        return super.visitMethod(access, mname, descriptor, signature, exceptions)
                    }

                    override fun visitEnd() {
                        visitMethod(Opcodes.ACC_PRIVATE, "unimined\$addLanguages", "(L$modContainerIntl;L$side;)V", null, null).apply {
                            visitCode()
                            // val var3 = unimined$getDirectories(var1.getSource())
                            visitVarInsn(Opcodes.ALOAD, 1)
                            visitMethodInsn(
                                Opcodes.INVOKEINTERFACE,
                                modContainerIntl,
                                "getSource",
                                "()Ljava/io/File;",
                                true
                            )
                            visitMethodInsn(Opcodes.INVOKESTATIC, loadControllerIntl, "unimined\$getDirectories", "(Ljava/io/File;)[Ljava/io/File;", false)
                            visitVarInsn(Opcodes.ASTORE, 3)
                            // for (int var4 = 0; var4 < var3.length; ++var4) {
                            visitInsn(Opcodes.ICONST_0)
                            visitVarInsn(Opcodes.ISTORE, 4)
                            val l1 = Label()
                            visitLabel(l1)
                            visitVarInsn(Opcodes.ILOAD, 4)
                            visitVarInsn(Opcodes.ALOAD, 3)
                            visitInsn(Opcodes.ARRAYLENGTH)
                            val l2 = Label()
                            visitJumpInsn(Opcodes.IF_ICMPGE, l2)
                            // val var5 = var3[var4]
                            visitVarInsn(Opcodes.ALOAD, 3)
                            visitVarInsn(Opcodes.ILOAD, 4)
                            visitInsn(Opcodes.AALOAD)
                            visitVarInsn(Opcodes.ASTORE, 5)
                            // searchDirForLanguages(var5, "", var2)
                            visitVarInsn(Opcodes.ALOAD, 0)
                            visitVarInsn(Opcodes.ALOAD, 5)
                            visitLdcInsn("")
                            visitVarInsn(Opcodes.ALOAD, 2)
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, "searchDirForLanguages", "(Ljava/io/File;Ljava/lang/String;L$side;)V", false)
                            // var4++
                            visitIincInsn(4, 1)
                            visitJumpInsn(Opcodes.GOTO, l1)
                            visitLabel(l2)
                            visitInsn(Opcodes.RETURN)

                            visitMaxs(0, 0)
                            visitEnd()
                        }

                        super.visitEnd()
                    }
                }, 0)
                path.writeBytes(writer.toByteArray())
            }
        }
    }
}
