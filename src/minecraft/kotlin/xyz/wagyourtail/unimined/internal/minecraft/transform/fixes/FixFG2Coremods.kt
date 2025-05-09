package xyz.wagyourtail.unimined.internal.minecraft.transform.fixes

import org.objectweb.asm.*
import java.nio.file.FileSystem
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.writeBytes

object FixFG2Coremods {

    val modList = listOf(
        "cpw/mods/fml/relauncher/CoreModManager.class",
        "net/minecraftforge/fml/relauncher/CoreModManager.class"
    )

    fun fixCoremods(fs: FileSystem) {
        for (file in modList) {
            val path = fs.getPath(file)
            if (path.exists()) {
                val reader = path.inputStream().use { ClassReader(it) }
                val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
                reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
                    override fun visitMethod(
                        access: Int,
                        name: String?,
                        descriptor: String?,
                        signature: String?,
                        exceptions: Array<out String>?
                    ): MethodVisitor {
                        if (name == "discoverCoreMods") {
                            return object : MethodVisitor(api, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                                override fun visitMethodInsn(
                                    opcode: Int,
                                    owner: String,
                                    name: String,
                                    descriptor: String,
                                    isInterface: Boolean
                                ) {
                                    // 1.7-1.11
                                    if (owner.endsWith("FileListHelper") && name == "sortFileList") {
                                        // call File[] unimined$addClasspath(File[] files)
                                        super.visitMethodInsn(
                                            Opcodes.INVOKESTATIC,
                                            file.substring(0, file.length - 6),
                                            "unimined\$addClasspath",
                                            "([Ljava/io/File;)[Ljava/io/File;",
                                            false
                                        )
                                    }
                                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                                    // 1.12.2
                                    if (owner.endsWith("LibraryManager") && name == "gatherLegacyCanidates") {
                                        super.visitInsn(Opcodes.DUP)
                                        // new File[0];
                                        super.visitLdcInsn(0)
                                        super.visitTypeInsn(Opcodes.ANEWARRAY, "java/io/File")
                                        // call File[] unimined$addClasspath(File[] files)
                                        super.visitMethodInsn(
                                            Opcodes.INVOKESTATIC,
                                            file.substring(0, file.length - 6),
                                            "unimined\$addClasspath",
                                            "([Ljava/io/File;)[Ljava/io/File;",
                                            false
                                        )
                                        // Arrays.asList
                                        super.visitMethodInsn(
                                            Opcodes.INVOKESTATIC,
                                            "java/util/Arrays",
                                            "asList",
                                            "([Ljava/lang/Object;)Ljava/util/List;",
                                            false
                                        )
                                        // List<File>.addAll(Collection<? extends File> c)
                                        super.visitMethodInsn(
                                            Opcodes.INVOKEINTERFACE,
                                            "java/util/List",
                                            "addAll",
                                            "(Ljava/util/Collection;)Z",
                                            true
                                        )
                                        super.visitInsn(Opcodes.POP)
                                    }
                                }
                            }
                        } else if (name == "handleCascadingTweak") {
                            return object : MethodVisitor(api, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                                override fun visitCode() {
                                    super.visitCode()
                                    // if unimined$isOnClasspath(arg0) {
                                    // tweaker.injectCascadingTweak(arg2);
                                    // tweakSorting.put(arg2, arg4);
                                    // return;
                                    // }
                                    visitVarInsn(Opcodes.ALOAD, 0)
                                    visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        file.substring(0, file.length - 6),
                                        "unimined\$isOnClasspath",
                                        "(Ljava/io/File;)Z",
                                        false
                                    )
                                    val l0 = Label()
                                    visitJumpInsn(Opcodes.IFEQ, l0)
                                    visitFrame(Opcodes.F_SAME, 0, null, 0, null)
                                    // cpw.mods.fml.relauncher.CoreModManager#tweaker or net.minecraftforge.fml.relauncher.CoreModManager#tweaker
                                    visitFieldInsn(
                                        Opcodes.GETSTATIC,
                                        file.substring(0, file.length - 6),
                                        "tweaker",
                                        if (file.startsWith("cpw")) {
                                            "Lcpw/mods/fml/common/launcher/FMLTweaker;"
                                        } else {
                                            "Lnet/minecraftforge/fml/common/launcher/FMLTweaker;"
                                        }
                                    )
                                    visitVarInsn(Opcodes.ALOAD, 2)
                                    visitMethodInsn(
                                        Opcodes.INVOKEVIRTUAL,
                                            if (file.startsWith("cpw")) {
                                                "cpw/mods/fml/common/launcher/FMLTweaker"
                                            } else {
                                                "net/minecraftforge/fml/common/launcher/FMLTweaker"
                                            },
                                        "injectCascadingTweak",
                                        "(Ljava/lang/String;)V",
                                        false
                                    )
                                    visitFieldInsn(
                                        Opcodes.GETSTATIC,
                                        file.substring(0, file.length - 6),
                                        "tweakSorting",
                                        "Ljava/util/Map;"
                                    )
                                    visitVarInsn(Opcodes.ALOAD, 2)
                                    visitVarInsn(Opcodes.ALOAD, 4)
                                    visitMethodInsn(
                                        Opcodes.INVOKEINTERFACE,
                                        "java/util/Map",
                                        "put",
                                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                                        true
                                    )
                                    visitInsn(Opcodes.POP)
                                    visitInsn(Opcodes.RETURN)
                                    visitLabel(l0)
                                    visitFrame(Opcodes.F_SAME, 0, null, 0, null)
                                }
                            }
                        } else {
                            return super.visitMethod(access, name, descriptor, signature, exceptions)
                        }
                    }

                    override fun visitEnd() {
                        // create method
                        // public static File[] unimined$addClasspath(File[] files) {
                        //     ArrayList l = new ArrayList();
                        //     for (String file : System.getProperty("java.class.path").split(File.pathSeparator)) {
                        //         l.add(new File(file));
                        //     }
                        //    l.addAll(Arrays.asList(files));
                        //    return (File[]) l.toArray(new File[0]);
                        // }
                        val mv = super.visitMethod(
                            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                            "unimined\$addClasspath",
                            "([Ljava/io/File;)[Ljava/io/File;",
                            null,
                            null
                        )
                        mv.visitCode()
                        mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList")
                        mv.visitInsn(Opcodes.DUP)
                        mv.visitMethodInsn(
                            Opcodes.INVOKESPECIAL,
                            "java/util/ArrayList",
                            "<init>",
                            "()V",
                            false
                        )
                        mv.visitVarInsn(Opcodes.ASTORE, 1)
                        mv.visitLdcInsn("java.class.path")
                        mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "java/lang/System",
                            "getProperty",
                            "(Ljava/lang/String;)Ljava/lang/String;",
                            false
                        )
                        mv.visitFieldInsn(
                            Opcodes.GETSTATIC,
                            "java/io/File",
                            "pathSeparator",
                            "Ljava/lang/String;"
                        )
                        mv.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            "java/lang/String",
                            "split",
                            "(Ljava/lang/String;)[Ljava/lang/String;",
                            false
                        )
                        mv.visitVarInsn(Opcodes.ASTORE, 2)
                        mv.visitVarInsn(Opcodes.ALOAD, 2)
                        mv.visitInsn(Opcodes.ARRAYLENGTH)
                        mv.visitVarInsn(Opcodes.ISTORE, 3)
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitVarInsn(Opcodes.ISTORE, 4)
                        val l0 = Label()
                        mv.visitLabel(l0)
                        mv.visitFrame(
                            Opcodes.F_FULL,
                            5,
                            arrayOf<Any?>("[Ljava/io/File;", "java/util/ArrayList", "[Ljava/lang/String;", Opcodes.INTEGER, Opcodes.INTEGER),
                            0,
                            arrayOf()
                        )
                        mv.visitVarInsn(Opcodes.ILOAD, 4)
                        mv.visitVarInsn(Opcodes.ILOAD, 3)
                        val l1 = Label()
                        mv.visitJumpInsn(Opcodes.IF_ICMPGE, l1)
                        mv.visitVarInsn(Opcodes.ALOAD, 2)
                        mv.visitVarInsn(Opcodes.ILOAD, 4)
                        mv.visitInsn(Opcodes.AALOAD)
                        mv.visitVarInsn(Opcodes.ASTORE, 5)
                        mv.visitVarInsn(Opcodes.ALOAD, 1)
                        mv.visitTypeInsn(Opcodes.NEW, "java/io/File")
                        mv.visitInsn(Opcodes.DUP)
                        mv.visitVarInsn(Opcodes.ALOAD, 5)
                        mv.visitMethodInsn(
                            Opcodes.INVOKESPECIAL,
                            "java/io/File",
                            "<init>",
                            "(Ljava/lang/String;)V",
                            false
                        )
                        mv.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            "java/util/ArrayList",
                            "add",
                            "(Ljava/lang/Object;)Z",
                            false
                        )
                        mv.visitInsn(Opcodes.POP)
                        mv.visitIincInsn(4, 1)
                        mv.visitJumpInsn(Opcodes.GOTO, l0)
                        mv.visitLabel(l1)
                        mv.visitFrame(
                            Opcodes.F_CHOP,
                            2,
                            null,
                            0,
                            null
                        )
                        mv.visitVarInsn(Opcodes.ALOAD, 1)
                        mv.visitVarInsn(Opcodes.ALOAD, 0)
                        mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "java/util/Arrays",
                            "asList",
                            "([Ljava/lang/Object;)Ljava/util/List;",
                            false
                        )
                        mv.visitMethodInsn(
                            Opcodes.INVOKEINTERFACE,
                            "java/util/List",
                            "addAll",
                            "(Ljava/util/Collection;)Z",
                            true
                        )
                        mv.visitInsn(Opcodes.POP)
                        mv.visitVarInsn(Opcodes.ALOAD, 1)
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/io/File")
                        mv.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            "java/util/ArrayList",
                            "toArray",
                            "([Ljava/lang/Object;)[Ljava/lang/Object;",
                            false
                        )
                        mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/io/File;")
                        mv.visitInsn(Opcodes.ARETURN)
                        mv.visitMaxs(4, 6)
                        mv.visitEnd()
                        // create method
                        // public static boolean unimined$isOnClasspath(File file) {
                        //     for (String path : System.getProperty("java.class.path").split(File.pathSeparator)) {
                        //         if (file.equals(new File(path))) {
                        //             return true;
                        //         }
                        //     }
                        //     return false;
                        // }
                        val mv2 = super.visitMethod(
                            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                            "unimined\$isOnClasspath",
                            "(Ljava/io/File;)Z",
                            null,
                            null
                        )
                        mv2.visitCode()
                        mv2.visitLdcInsn("java.class.path")
                        mv2.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "java/lang/System",
                            "getProperty",
                            "(Ljava/lang/String;)Ljava/lang/String;",
                            false
                        )
                        mv2.visitFieldInsn(
                            Opcodes.GETSTATIC,
                            "java/io/File",
                            "pathSeparator",
                            "Ljava/lang/String;"
                        )
                        mv2.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            "java/lang/String",
                            "split",
                            "(Ljava/lang/String;)[Ljava/lang/String;",
                            false
                        )
                        mv2.visitVarInsn(Opcodes.ASTORE, 1)
                        mv2.visitVarInsn(Opcodes.ALOAD, 1)
                        mv2.visitInsn(Opcodes.ARRAYLENGTH)
                        mv2.visitVarInsn(Opcodes.ISTORE, 2)
                        mv2.visitInsn(Opcodes.ICONST_0)
                        mv2.visitVarInsn(Opcodes.ISTORE, 3)
                        val l2 = Label()
                        mv2.visitLabel(l2)
                        mv2.visitFrame(
                            Opcodes.F_APPEND,
                            3,
                            arrayOf<Any?>("[Ljava/lang/String;", Opcodes.INTEGER, Opcodes.INTEGER),
                            0,
                            null
                        )
                        mv2.visitVarInsn(Opcodes.ILOAD, 3)
                        mv2.visitVarInsn(Opcodes.ILOAD, 2)
                        val l3 = Label()
                        mv2.visitJumpInsn(Opcodes.IF_ICMPGE, l3)
                        mv2.visitVarInsn(Opcodes.ALOAD, 1)
                        mv2.visitVarInsn(Opcodes.ILOAD, 3)
                        mv2.visitInsn(Opcodes.AALOAD)
                        mv2.visitVarInsn(Opcodes.ASTORE, 4)
                        mv2.visitVarInsn(Opcodes.ALOAD, 0)
                        mv2.visitTypeInsn(Opcodes.NEW, "java/io/File")
                        mv2.visitInsn(Opcodes.DUP)
                        mv2.visitVarInsn(Opcodes.ALOAD, 4)
                        mv2.visitMethodInsn(
                            Opcodes.INVOKESPECIAL,
                            "java/io/File",
                            "<init>",
                            "(Ljava/lang/String;)V",
                            false
                        )
                        mv2.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            "java/io/File",
                            "equals",
                            "(Ljava/lang/Object;)Z",
                            false
                        )
                        val l4 = Label()
                        mv2.visitJumpInsn(Opcodes.IFEQ, l4)
                        mv2.visitInsn(Opcodes.ICONST_1)
                        mv2.visitInsn(Opcodes.IRETURN)
                        mv2.visitLabel(l4)
                        mv2.visitFrame(
                            Opcodes.F_SAME,
                            0,
                            null,
                            0,
                            null
                        )
                        mv2.visitIincInsn(3, 1)
                        mv2.visitJumpInsn(Opcodes.GOTO, l2)
                        mv2.visitLabel(l3)
                        mv2.visitFrame(
                            Opcodes.F_CHOP,
                            2,
                            null,
                            0,
                            null
                        )
                        mv2.visitInsn(Opcodes.ICONST_0)
                        mv2.visitInsn(Opcodes.IRETURN)
                        mv2.visitMaxs(4, 5)
                        mv2.visitEnd()
                        super.visitEnd()
                    }
                }, 0)
                path.writeBytes(writer.toByteArray())

            }
        }
    }

}