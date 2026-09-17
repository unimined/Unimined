package xyz.wagyourtail.unimined.internal.minecraft.transform.fixes

import org.objectweb.asm.*
import org.objectweb.asm.tree.ClassNode
import java.nio.file.FileSystem
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.writeBytes

object FixFG1ResourceLoading {
    val loadController = listOf(
        "cpw/mods/fml/common/LoadController.class",
        "net/minecraftforge/fml/common/LoadController.class",
    )

    val directoryDiscoverer = listOf(
        "cpw/mods/fml/common/discovery/DirectoryDiscoverer.class",
        "net/minecraftforge/fml/common/discovery/DirectoryDiscoverer.class",
    )

    fun fixResourceLoading(fs: FileSystem) {
        for (file in loadController) {
            val path = fs.getPath(file)
            if (path.exists()) {
                val reader = path.inputStream().use { ClassReader(it) }
                val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
                reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
                    override fun visitEnd() {
                        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "unimined\$getDirectories", "(Ljava/io/File;)[Ljava/io/File;", null, null).apply {
                            visitCode()
                            // val var1 = System.getenv("MOD_CLASSES")
                            visitLdcInsn("MOD_CLASSES")
                            visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "java/lang/System",
                                "getenv",
                                "(Ljava/lang/String;)Ljava/lang/String;",
                                false
                            )
                            visitVarInsn(Opcodes.ASTORE, 1)
                            // if (var1 == null) return;
                            visitVarInsn(Opcodes.ALOAD, 1)
                            val l1 = Label()
                            visitJumpInsn(Opcodes.IFNULL, l1)
                            //  val var2 = var2.split(File.pathSeparator)
                            visitVarInsn(Opcodes.ALOAD, 1)
                            visitFieldInsn(Opcodes.GETSTATIC, "java/io/File", "pathSeparator", "Ljava/lang/String;")
                            visitMethodInsn(
                                Opcodes.INVOKEVIRTUAL,
                                "java/lang/String",
                                "split",
                                "(Ljava/lang/String;)[Ljava/lang/String;",
                                false
                            )
                            visitVarInsn(Opcodes.ASTORE, 2)
                            //  List<File> var3 = new ArrayList<File>()
                            visitTypeInsn(Opcodes.NEW, "java/util/ArrayList")
                            visitInsn(Opcodes.DUP)
                            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false)
                            visitVarInsn(Opcodes.ASTORE, 3)
                            //  var var4 = null
                            visitInsn(Opcodes.ACONST_NULL)
                            visitVarInsn(Opcodes.ASTORE, 4)
                            //  for (int var5 = 0; var5 < var2.length; ++var5) {
                            visitInsn(Opcodes.ICONST_0)
                            visitVarInsn(Opcodes.ISTORE, 5)
                            val l5 = Label()
                            visitLabel(l5)
                            visitVarInsn(Opcodes.ILOAD, 5)
                            visitVarInsn(Opcodes.ALOAD, 2)
                            visitInsn(Opcodes.ARRAYLENGTH)
                            val l6 = Label()
                            visitJumpInsn(Opcodes.IF_ICMPGE, l6)
                            //    val var6 = var2[var5].split("%%", 2)
                            visitVarInsn(Opcodes.ALOAD, 2)
                            visitVarInsn(Opcodes.ILOAD, 5)
                            visitInsn(Opcodes.AALOAD)
                            visitLdcInsn("%%")
                            visitInsn(Opcodes.ICONST_2)
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "split", "(Ljava/lang/String;I)[Ljava/lang/String;", false)
                            visitVarInsn(Opcodes.ASTORE, 6)
                            //    val var7 = new File(var6[1])
                            visitTypeInsn(Opcodes.NEW, "java/io/File")
                            visitInsn(Opcodes.DUP)
                            visitVarInsn(Opcodes.ALOAD, 6)
                            visitInsn(Opcodes.ICONST_1)
                            visitInsn(Opcodes.AALOAD)
                            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false)
                            visitVarInsn(Opcodes.ASTORE, 7)
                            //    if (var7.equals(var0.getSource())) {
                            visitVarInsn(Opcodes.ALOAD, 7)
                            visitVarInsn(Opcodes.ALOAD, 0)
                            val l7 = Label()
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "equals", "(Ljava/lang/Object;)Z", false)
                            visitJumpInsn(Opcodes.IFEQ, l7)
                            //      var4 = var6[0]
                            visitVarInsn(Opcodes.ALOAD, 6)
                            visitInsn(Opcodes.ICONST_0)
                            visitInsn(Opcodes.AALOAD)
                            visitVarInsn(Opcodes.ASTORE, 4)
                            //      break;
                            visitJumpInsn(Opcodes.GOTO, l6)
                            //    }
                            visitLabel(l7)
                            // }
                            visitIincInsn(5, 1)
                            visitJumpInsn(Opcodes.GOTO, l5)
                            visitLabel(l6)
                            // if (var4 == null) return;
                            visitVarInsn(Opcodes.ALOAD, 4)
                            visitJumpInsn(Opcodes.IFNULL, l1)
                            //  for (int var5 = 0; var5 < var2.length; ++var5) {
                            visitInsn(Opcodes.ICONST_0)
                            visitVarInsn(Opcodes.ISTORE, 5)
                            val l2 = Label()
                            visitLabel(l2)
                            visitVarInsn(Opcodes.ILOAD, 5)
                            visitVarInsn(Opcodes.ALOAD, 2)
                            visitInsn(Opcodes.ARRAYLENGTH)
                            val l3 = Label()
                            visitJumpInsn(Opcodes.IF_ICMPGE, l3)
                            //      val var6 = var2[var5]
                            visitVarInsn(Opcodes.ALOAD, 2)
                            visitVarInsn(Opcodes.ILOAD, 5)
                            visitInsn(Opcodes.AALOAD)
                            visitVarInsn(Opcodes.ASTORE, 6)
                            //      if (var6.startsWith(var4)) {
                            visitVarInsn(Opcodes.ALOAD, 6)
                            visitVarInsn(Opcodes.ALOAD, 4)
                            visitMethodInsn(
                                Opcodes.INVOKEVIRTUAL,
                                "java/lang/String",
                                "startsWith",
                                "(Ljava/lang/String;)Z",
                                false
                            )
                            val l4 = Label()
                            visitJumpInsn(Opcodes.IFEQ, l4)
                            //        var3.add(new File(var6.substring(var4.length() + 2)))
                            visitVarInsn(Opcodes.ALOAD, 3)
                            visitTypeInsn(Opcodes.NEW, "java/io/File")
                            visitInsn(Opcodes.DUP)
                            visitVarInsn(Opcodes.ALOAD, 6)
                            visitVarInsn(Opcodes.ALOAD, 4)
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)
                            visitInsn(Opcodes.ICONST_2)
                            visitInsn(Opcodes.IADD)
                            visitMethodInsn(
                                Opcodes.INVOKEVIRTUAL,
                                "java/lang/String",
                                "substring",
                                "(I)Ljava/lang/String;",
                                false
                            )
                            visitMethodInsn(
                                Opcodes.INVOKESPECIAL,
                                "java/io/File",
                                "<init>",
                                "(Ljava/lang/String;)V",
                                false
                            )
                            visitMethodInsn(
                                Opcodes.INVOKEINTERFACE,
                                "java/util/List",
                                "add",
                                "(Ljava/lang/Object;)Z",
                                true
                            )
                            visitInsn(Opcodes.POP)
                            //      }
                            visitLabel(l4)
                            visitIincInsn(5, 1)
                            visitJumpInsn(Opcodes.GOTO, l2)
                            //  }
                            visitLabel(l3)
                            //  return var3.toArray(new File[0])
                            visitVarInsn(Opcodes.ALOAD, 3)
                            visitInsn(Opcodes.ICONST_0)
                            visitTypeInsn(Opcodes.ANEWARRAY, "java/io/File")
                            visitMethodInsn(
                                Opcodes.INVOKEINTERFACE,
                                "java/util/List",
                                "toArray",
                                "([Ljava/lang/Object;)[Ljava/lang/Object;",
                                true
                            )
                            visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/io/File;")
                            visitInsn(Opcodes.ARETURN)
                            //  return new File[1] { var0 };
                            visitLabel(l1)
                            visitInsn(Opcodes.ICONST_1)
                            visitTypeInsn(Opcodes.ANEWARRAY, "java/io/File")
                            visitInsn(Opcodes.DUP)
                            visitInsn(Opcodes.ICONST_0)
                            visitVarInsn(Opcodes.ALOAD, 0)
                            visitInsn(Opcodes.AASTORE)
                            visitInsn(Opcodes.ARETURN)

                            visitMaxs(0, 0)
                            visitEnd()
                        }

                        super.visitEnd()
                    }
                }, 0)
                path.writeBytes(writer.toByteArray())
            }
        }

        for (file in directoryDiscoverer) {
            val path = fs.getPath(file)
            if (path.exists()) {

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
                        name: String?,
                        descriptor: String?,
                        signature: String?,
                        exceptions: Array<out String>?
                    ): MethodVisitor {
                        if (name == "exploreFileSystem") {
                            // replace new File call with unimined$findMcModInfo
                            return object : MethodVisitor(api, super.visitMethod(access, name, descriptor, signature, exceptions)) {

                                var first = true
                                override fun visitTypeInsn(opcode: Int, type: String?) {
                                    if (opcode == Opcodes.NEW && type == "java/io/File") {
                                        if (!first) {
                                            super.visitTypeInsn(opcode, type)
                                            return
                                        }
                                    } else {
                                        super.visitTypeInsn(opcode, type)
                                    }
                                }

                                override fun visitInsn(opcode: Int) {
                                    if (opcode == Opcodes.DUP) {
                                        if (!first) {
                                            super.visitInsn(opcode)
                                            return
                                        }
                                        super.visitVarInsn(Opcodes.ALOAD, 0)
                                    } else {
                                        super.visitInsn(opcode)
                                    }
                                }

                                override fun visitMethodInsn(
                                    opcode: Int,
                                    owner: String?,
                                    name: String?,
                                    descriptor: String?,
                                    isInterface: Boolean
                                ) {
                                    if (name == "<init>" && owner == "java/io/File") {
                                        if (!first) {
                                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                                            return
                                        }
                                        first = false
                                        // unimined$findMcModInfo(var1, var2)
                                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, "unimined\$findMcModInfo", "(Ljava/io/File;Ljava/lang/String;)Ljava/io/File;", false)
                                    } else {
                                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                                    }
                                }

                            }
                        } else {
                            return super.visitMethod(access, name, descriptor, signature, exceptions)
                        }
                    }

                    override fun visitEnd() {
                        visitMethod(Opcodes.ACC_PRIVATE, "unimined\$findMcModInfo", "(Ljava/io/File;Ljava/lang/String;)Ljava/io/File;", null, null).apply {
                            visitCode()
                            // val var3 = LoadController.unimined$getDirectories(var1)
                            visitVarInsn(Opcodes.ALOAD, 1)
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
                            // val var6 = new File(var5, var2)
                            visitTypeInsn(Opcodes.NEW, "java/io/File")
                            visitInsn(Opcodes.DUP)
                            visitVarInsn(Opcodes.ALOAD, 5)
                            visitVarInsn(Opcodes.ALOAD, 2)
                            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/io/File;Ljava/lang/String;)V", false)
                            visitVarInsn(Opcodes.ASTORE, 6)
                            // if (var6.exists()) {
                            visitVarInsn(Opcodes.ALOAD, 6)
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "exists", "()Z", false)
                            val l3 = Label()
                            visitJumpInsn(Opcodes.IFEQ, l3)
                            // return var6
                            visitVarInsn(Opcodes.ALOAD, 6)
                            visitInsn(Opcodes.ARETURN)
                            // }
                            visitLabel(l3)
                            visitIincInsn(4, 1)
                            visitJumpInsn(Opcodes.GOTO, l1)
                            visitLabel(l2)
                            // return new File(var1, var2)
                            visitTypeInsn(Opcodes.NEW, "java/io/File")
                            visitInsn(Opcodes.DUP)
                            visitVarInsn(Opcodes.ALOAD, 1)
                            visitVarInsn(Opcodes.ALOAD, 2)
                            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/io/File;Ljava/lang/String;)V", false)
                            visitInsn(Opcodes.ARETURN)

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
