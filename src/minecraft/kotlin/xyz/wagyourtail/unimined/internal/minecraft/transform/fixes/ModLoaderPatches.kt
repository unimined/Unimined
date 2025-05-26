package xyz.wagyourtail.unimined.internal.minecraft.transform.fixes

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.nio.file.FileSystem
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

object ModLoaderPatches {
    fun fixURIisNotHierarchicalException(fileSystem: FileSystem) {
        val modLoader = fileSystem.getPath("/ModLoader.class")
        if (!modLoader.exists()) return
        val classNode = ClassNode()
        val classReader = ClassReader(modLoader.readBytes())
        classReader.accept(classNode, 0)

        if (classNode.fields.any { it.name == "fmlMarker" }) return
        if (fileSystem.getPath("/cpw/mods/fml/common/modloader/ModLoaderHelper.class").exists()) return

        if (classNode.methods.any { it.name == "readFromClassPath" }) {
            println("ModLoader patch using newer method")
            newerURIFix(classNode)
        } else {
            println("ModLoader patch using older method")
            olderURIFix(classNode)
        }
        val classWriter = ClassWriterASM(fileSystem)
        classNode.accept(classWriter)
        modLoader.writeBytes(classWriter.toByteArray(), StandardOpenOption.TRUNCATE_EXISTING)
    }

    private fun newerURIFix(classNode: ClassNode) {
        val method = classNode.methods.first { (it.name == "init" || it.name == "a") && it.desc == "()V" }
        // find the lines
        //     L92
        //    LINENUMBER 809 L92
        //    NEW java/io/File
        //    DUP
        //    LDC LModLoader;.class
        //    INVOKEVIRTUAL java/lang/Class.getProtectionDomain ()Ljava/security/ProtectionDomain;
        //    INVOKEVIRTUAL java/security/ProtectionDomain.getCodeSource ()Ljava/security/CodeSource;
        //    INVOKEVIRTUAL java/security/CodeSource.getLocation ()Ljava/net/URL;
        //    INVOKEVIRTUAL java/net/URL.toURI ()Ljava/net/URI;
        //    INVOKESPECIAL java/io/File.<init> (Ljava/net/URI;)V
        //    ASTORE 2
        //   L93
        //    LINENUMBER 810 L93
        //    GETSTATIC ModLoader.modDir : Ljava/io/File;
        //    INVOKEVIRTUAL java/io/File.mkdirs ()Z
        //    POP
        //   L94
        //    LINENUMBER 811 L94
        //    GETSTATIC ModLoader.modDir : Ljava/io/File;
        //    INVOKESTATIC ModLoader.readFromModFolder (Ljava/io/File;)V
        //   L95
        //    LINENUMBER 812 L95
        //    ALOAD 2
        //    INVOKESTATIC ModLoader.readFromClassPath (Ljava/io/File;)V
        // and replace them with
        //     this.modDir.mkdirs();
        //     readFromModFolder(this.modDir);
        //     for (String path : System.getProperty("java.class.path").split(File.pathSeparator)) {
        //         readFromClassPath(new File(path));
        //     }

        val instructions = method.instructions
        val newInstructions = InsnList()
        val iterator = instructions.iterator()
        var slice = false

        while (iterator.hasNext()) {
            val firstInsn = iterator.next()

            if (firstInsn is TypeInsnNode && firstInsn.desc == "java/io/File" && firstInsn.opcode == Opcodes.NEW) {
                val hold = mutableListOf<AbstractInsnNode>(firstInsn)
                var next: AbstractInsnNode = firstInsn
                while (next !is VarInsnNode && iterator.hasNext()) {
                    next = iterator.next()
                    hold.add(next)
                    if (next is MethodInsnNode && next.name == "getProtectionDomain") {
                        slice = true
                        break
                    }
                }
                if (!slice) {
                    for (i in hold) {
                        newInstructions.add(i)
                    }
                    continue
                }
            }

            // detect 'NEW java/io/File'
            if (slice) {
                val startLbl = LabelNode()
                val endLbl = LabelNode()

                newInstructions.add(startLbl)

                // find where it's stored
                var storeInsn = iterator.next()
                while (storeInsn !is VarInsnNode || storeInsn.opcode != Opcodes.ASTORE) {
//                        // get name of opcode
//                        val opcodeName = Opcodes::class.java.fields.first { it.get(null) == storeInsn.opcode }.name
//                        System.err.println("INSN: ${opcodeName}")
                    storeInsn = iterator.next()
                }
                val baseLVIndex = 2 + 1
                method.localVariables.add(
                    LocalVariableNode(
                        "path",
                        "[Ljava/lang/String;",
                        null,
                        startLbl,
                        endLbl,
                        baseLVIndex
                    )
                )

                if (classNode.methods.any { it.name == "readFromModFolder" }) {
                    // ModLoader.modDir.mkdirs();
                    newInstructions.add(FieldInsnNode(Opcodes.GETSTATIC, "ModLoader", "modDir", "Ljava/io/File;"))
                    newInstructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/File", "mkdirs", "()Z", false))
                    newInstructions.add(InsnNode(Opcodes.POP))
                    // readFromModFolder(ModLoader.modDir);
                    newInstructions.add(FieldInsnNode(Opcodes.GETSTATIC, "ModLoader", "modDir", "Ljava/io/File;"))
                    newInstructions.add(
                        MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "ModLoader",
                            "readFromModFolder",
                            "(Ljava/io/File;)V",
                            false
                        )
                    )
                }
                // var paths = System.getProperty("java.class.path")
                newInstructions.add(LdcInsnNode("java.class.path"))
                newInstructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/System",
                        "getProperty",
                        "(Ljava/lang/String;)Ljava/lang/String;",
                        false
                    )
                )
                // paths = paths.split(File.pathSeparator)
                newInstructions.add(
                    FieldInsnNode(
                        Opcodes.GETSTATIC, "java/io/File", "pathSeparator", "Ljava/lang/String;"
                    )
                )
                newInstructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/String",
                        "split",
                        "(Ljava/lang/String;)[Ljava/lang/String;",
                        false
                    )
                )
                newInstructions.add(VarInsnNode(Opcodes.ASTORE, baseLVIndex))
                // for (String path : paths)) {
                //     readFromClassPath(new File(path));
                // }
                newInstructions.add(VarInsnNode(Opcodes.ALOAD, baseLVIndex))
                newInstructions.add(InsnNode(Opcodes.ARRAYLENGTH))
                newInstructions.add(VarInsnNode(Opcodes.ISTORE, baseLVIndex + 1))
                newInstructions.add(InsnNode(Opcodes.ICONST_0))
                newInstructions.add(VarInsnNode(Opcodes.ISTORE, baseLVIndex + 2))
                val loopStart = LabelNode()
                newInstructions.add(loopStart)
                newInstructions.add(VarInsnNode(Opcodes.ILOAD, baseLVIndex + 2))
                newInstructions.add(VarInsnNode(Opcodes.ILOAD, baseLVIndex + 1))
                val loopEnd = LabelNode()
                newInstructions.add(JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd))
                newInstructions.add(TypeInsnNode(Opcodes.NEW, "java/io/File"))
                newInstructions.add(InsnNode(Opcodes.DUP))
                newInstructions.add(VarInsnNode(Opcodes.ALOAD, baseLVIndex))
                newInstructions.add(VarInsnNode(Opcodes.ILOAD, baseLVIndex + 2))
                newInstructions.add(InsnNode(Opcodes.AALOAD))
                newInstructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "java/io/File",
                        "<init>",
                        "(Ljava/lang/String;)V",
                        false
                    )
                )
                newInstructions.add(VarInsnNode(Opcodes.ASTORE, baseLVIndex + 3))
                newInstructions.add(VarInsnNode(Opcodes.ALOAD, baseLVIndex + 3))
                newInstructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "ModLoader",
                        "readFromClassPath",
                        "(Ljava/io/File;)V",
                        false
                    )
                )
                newInstructions.add(VarInsnNode(Opcodes.ILOAD, baseLVIndex + 2))
                newInstructions.add(InsnNode(Opcodes.ICONST_1))
                newInstructions.add(InsnNode(Opcodes.IADD))
                newInstructions.add(VarInsnNode(Opcodes.ISTORE, baseLVIndex + 2))
                newInstructions.add(JumpInsnNode(Opcodes.GOTO, loopStart))
                newInstructions.add(loopEnd)
                // skip the original instructions
                var readFromClassPath = false
                var readFromModsDir = !classNode.methods.any { it.name == "readFromModFolder" }
                while (iterator.hasNext()) {
                    val insn = iterator.next()
                    if (insn is MethodInsnNode && insn.owner == "ModLoader" && insn.desc == "(Ljava/io/File;)V" && insn.opcode == Opcodes.INVOKESTATIC) {
                        if (insn.name == "readFromClassPath") {
                            readFromClassPath = true
                        } else if (insn.name == "readFromModFolder") {
                            readFromModsDir = true
                        }
                        if (readFromClassPath && readFromModsDir) {
                            break
                        }
                    }
                }
                newInstructions.add(endLbl)
                slice = false
            } else {
                newInstructions.add(firstInsn)
            }
        }
        method.instructions = newInstructions
//            classNode.methods.remove(method)
//            val newMethod = MethodNode(method.access, method.name, method.desc, method.signature, method.exceptions.toTypedArray())
//            newMethod.instructions = newInstructions
//            classNode.methods.add(newMethod)
    }

    private fun olderURIFix(classNode: ClassNode) {
        val method = classNode.methods.first { it.name == "<clinit>" }

        // extract part of this function out to a new function
        // and call it from the original function with the whole classpath
        // copy the clinit to new method
        val newMethod = MethodNode(Opcodes.ACC_STATIC, "readFromClassPath", "(Ljava/io/File;)V", null, null)
        val newInstructions = InsnList()
        newMethod.instructions = newInstructions

        val iter = method.instructions.iterator()
        var foundFile = false
        val preFoundFile = InsnList()
        val clonedLabels = mutableMapOf<LabelNode, LabelNode>()
        while (iter.hasNext()) {
            val firstInsn = iter.next()
            // remove new file insns
            if (!foundFile && firstInsn is TypeInsnNode && firstInsn.desc == "java/io/File") {
                // skip
                while (iter.hasNext()) {
                    val insn = iter.next()
                    if (insn is VarInsnNode && insn.opcode == Opcodes.ASTORE) {
                        break
                    }
                }
                foundFile = true
                continue
            }
            if (!foundFile) {
                if (firstInsn is LabelNode) {
                    newInstructions.add(firstInsn)
                    clonedLabels[firstInsn] = LabelNode()
                    preFoundFile.add(clonedLabels[firstInsn])
                } else {
                    preFoundFile.add(firstInsn.clone(clonedLabels))
                }
            } else {
                newInstructions.add(firstInsn)
            }
        }
        if (!foundFile) {
            throw IllegalStateException("Could not find file type")
        }
        classNode.methods.add(newMethod)
        classNode.methods.remove(method)

        // add new method to clinit
        val newClinit = MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        newClinit.instructions = preFoundFile
        classNode.methods.add(newClinit)
        newClinit.visitCode()
        newClinit.visitLdcInsn("java.class.path")
        newClinit.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/System",
            "getProperty",
            "(Ljava/lang/String;)Ljava/lang/String;",
            false
        )
        newClinit.visitFieldInsn(Opcodes.GETSTATIC, "java/io/File", "pathSeparator", "Ljava/lang/String;")
        newClinit.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "split",
            "(Ljava/lang/String;)[Ljava/lang/String;",
            false
        )
        newClinit.visitVarInsn(Opcodes.ASTORE, 0)
        newClinit.visitInsn(Opcodes.ICONST_0)
        newClinit.visitVarInsn(Opcodes.ISTORE, 1)
        val loopStart = Label()
        newClinit.visitLabel(loopStart)
        newClinit.visitVarInsn(Opcodes.ILOAD, 1)
        newClinit.visitVarInsn(Opcodes.ALOAD, 0)
        newClinit.visitInsn(Opcodes.ARRAYLENGTH)
        val loopEnd = Label()
        newClinit.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd)
        newClinit.visitTypeInsn(Opcodes.NEW, "java/io/File")
        newClinit.visitInsn(Opcodes.DUP)
        newClinit.visitVarInsn(Opcodes.ALOAD, 0)
        newClinit.visitVarInsn(Opcodes.ILOAD, 1)
        newClinit.visitInsn(Opcodes.AALOAD)
        newClinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false)
        newClinit.visitVarInsn(Opcodes.ASTORE, 2)
        newClinit.visitVarInsn(Opcodes.ALOAD, 2)
        newClinit.visitMethodInsn(Opcodes.INVOKESTATIC, "ModLoader", "readFromClassPath", "(Ljava/io/File;)V", false)
        newClinit.visitIincInsn(1, 1)
        newClinit.visitJumpInsn(Opcodes.GOTO, loopStart)
        newClinit.visitLabel(loopEnd)
        newClinit.visitInsn(Opcodes.RETURN)
        newClinit.visitMaxs(0, 0)
        newClinit.visitEnd()
    }

    fun fixLoadingModFromOtherPackages(fileSystem: FileSystem) {
        val modLoader = fileSystem.getPath("/ModLoader.class")
        if (!modLoader.exists()) return
        val classNode = ClassNode()
        val classReader = ClassReader(modLoader.readBytes())
        classReader.accept(classNode, 0)

        if (classNode.fields.any { it.name == "fmlMarker" }) return
        if (fileSystem.getPath("/cpw/mods/fml/common/modloader/ModLoaderHelper.class").exists()) return

        if (classNode.methods.any { it.name == "readFromClassPath" }) {
            println("ModLoader patch pkgs using newer method")
            newerPackageFix(classNode)
        } else {
            throw IllegalStateException("ModLoader patch pkgs should be run after other patches")
        }

        val classWriter = ClassWriterASM(fileSystem)
        classNode.accept(classWriter)
        modLoader.writeBytes(classWriter.toByteArray(), StandardOpenOption.TRUNCATE_EXISTING)
    }

    fun newerPackageFix(classNode: ClassNode) {
        // find
        // File[] files = source.listFiles();
        // replace with file tree walker to File[] files
        val method = classNode.methods.first { it.name == "readFromClassPath" }

        val instructions = method.instructions
        val newInstructions = InsnList()
        val iterator = instructions.iterator()
        var slice = false

        var foundFileArr = false

        while (iterator.hasNext()) {
            val insn = iterator.next()
            if (insn is MethodInsnNode && insn.name == "listFiles" && insn.owner == "java/io/File") {
                slice = true
            }
            if (slice) {
                // File.toPath()
                newInstructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "java/io/File",
                        "toPath",
                        "()Ljava/nio/file/Path;",
                        false
                    )
                )
                // new FileVisitOption[]
                newInstructions.add(InsnNode(Opcodes.ICONST_0))
                newInstructions.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/nio/file/FileVisitOption"))
                // Files.walk()
                newInstructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/nio/file/Files",
                        "walk",
                        "(Ljava/nio/file/Path;[Ljava/nio/file/FileVisitOption;)Ljava/util/stream/Stream;",
                        false
                    )
                )
                // Collectors.toList()
                newInstructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/util/stream/Collectors",
                        "toList",
                        "()Ljava/util/stream/Collector;",
                        false
                    )
                )
                // Stream.collect()
                newInstructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "java/util/stream/Stream",
                        "collect",
                        "(Ljava/util/stream/Collector;)Ljava/lang/Object;",
                        true
                    )
                )
                // List.toArray()
                newInstructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "java/util/List",
                        "toArray",
                        "()[Ljava/lang/Object;",
                        true
                    )
                )
                // (Object[]) List.toArray()
                val aStore = iterator.next()
                if (aStore !is VarInsnNode || aStore.opcode != Opcodes.ASTORE) {
                    throw IllegalStateException("Expected astore")
                }
                newInstructions.add(VarInsnNode(Opcodes.ASTORE, aStore.`var` + 1))
                // File[] files = new File[paths.length];
                newInstructions.add(VarInsnNode(Opcodes.ALOAD, aStore.`var` + 1))
                newInstructions.add(InsnNode(Opcodes.ARRAYLENGTH))
                newInstructions.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/io/File"))
                newInstructions.add(aStore)
                // for (int i = 0; i < paths.length; i++) {
                //     files[i] = ((Path) paths[i]).toFile();
                // }
                val loopStart = LabelNode()
                val loopEnd = LabelNode()
                newInstructions.add(InsnNode(Opcodes.ICONST_0))
                newInstructions.add(VarInsnNode(Opcodes.ISTORE, aStore.`var` + 2))
                newInstructions.add(loopStart)
                newInstructions.add(VarInsnNode(Opcodes.ILOAD, aStore.`var` + 2))
                newInstructions.add(VarInsnNode(Opcodes.ALOAD, aStore.`var` + 1))
                newInstructions.add(InsnNode(Opcodes.ARRAYLENGTH))
                newInstructions.add(JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd))
                newInstructions.add(VarInsnNode(Opcodes.ALOAD, aStore.`var`))
                newInstructions.add(VarInsnNode(Opcodes.ILOAD, aStore.`var` + 2))
                newInstructions.add(VarInsnNode(Opcodes.ALOAD, aStore.`var` + 1))
                newInstructions.add(VarInsnNode(Opcodes.ILOAD, aStore.`var` + 2))
                newInstructions.add(InsnNode(Opcodes.AALOAD))
                newInstructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "java/nio/file/Path",
                        "toFile",
                        "()Ljava/io/File;",
                        true
                    )
                )
                newInstructions.add(InsnNode(Opcodes.AASTORE))
                newInstructions.add(VarInsnNode(Opcodes.ILOAD, aStore.`var` + 2))
                newInstructions.add(InsnNode(Opcodes.ICONST_1))
                newInstructions.add(InsnNode(Opcodes.IADD))
                newInstructions.add(VarInsnNode(Opcodes.ISTORE, aStore.`var` + 2))
                newInstructions.add(JumpInsnNode(Opcodes.GOTO, loopStart))
                newInstructions.add(loopEnd)
                foundFileArr = true
                slice = false
            } else {
                if (foundFileArr) {
                    // replace
                    // String name = files[i].getName();
                    // with
                    // String name = files[i].toPath().relativize(arg0.toPath()).toString());
                    if (insn is MethodInsnNode && insn.name == "getName" && insn.owner == "java/io/File") {
                        newInstructions.add(
                            MethodInsnNode(
                                Opcodes.INVOKEVIRTUAL,
                                "java/io/File",
                                "toPath",
                                "()Ljava/nio/file/Path;",
                                false
                            )
                        )
                        newInstructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                        newInstructions.add(
                            MethodInsnNode(
                                Opcodes.INVOKEVIRTUAL,
                                "java/io/File",
                                "toPath",
                                "()Ljava/nio/file/Path;",
                                false
                            )
                        )
                        // dup2 and pop
                        newInstructions.add(InsnNode(Opcodes.DUP2))
                        newInstructions.add(InsnNode(Opcodes.POP))
                        newInstructions.add(
                            MethodInsnNode(
                                Opcodes.INVOKEINTERFACE,
                                "java/nio/file/Path",
                                "relativize",
                                "(Ljava/nio/file/Path;)Ljava/nio/file/Path;",
                                true
                            )
                        )
                        newInstructions.add(
                            MethodInsnNode(
                                Opcodes.INVOKEINTERFACE,
                                "java/nio/file/Path",
                                "toString",
                                "()Ljava/lang/String;",
                                true
                            )
                        )
                        val aStore2 = iterator.next()
                        if (aStore2 !is VarInsnNode || aStore2.opcode != Opcodes.ASTORE) {
                            throw IllegalStateException("Expected astore")
                        }
                        newInstructions.add(aStore2)
                        // pop
                        newInstructions.add(InsnNode(Opcodes.POP))
                        continue
                    }
                    if (insn is LdcInsnNode && insn.cst == "mod_") {
                        continue
                    }
                    // replace
                    // name.startsWith("mod_")
                    // with
                    // name.matches(".*mod_.*\\.class")
                    if (insn is MethodInsnNode && insn.name == "startsWith" && insn.owner == "java/lang/String") {
                        newInstructions.add(LdcInsnNode("(?:.*[/\\\\]|^)mod_.*\\.class"))
                        newInstructions.add(
                            MethodInsnNode(
                                Opcodes.INVOKEVIRTUAL,
                                "java/lang/String",
                                "matches",
                                "(Ljava/lang/String;)Z",
                                false
                            )
                        )
                        continue
                    }
                }

                newInstructions.add(insn)
            }
        }

        method.instructions = newInstructions

        // find addMod
        val addMod = classNode.methods.firstOrNull { it.name == "addMod" }
            ?: throw IllegalStateException("addMod not found")
        val addModInstructions = addMod.instructions
        val addModIterator = addModInstructions.iterator()
        val addModNewInstructions = InsnList()

        while (addModIterator.hasNext()) {
            val insn = addModIterator.next()
            // if insn is loadClass(String)
            if (insn is MethodInsnNode && insn.name == "loadClass" && insn.owner == "java/lang/ClassLoader") {
                // String.replace('/', '.')
                addModNewInstructions.add(LdcInsnNode("/"))
                addModNewInstructions.add(LdcInsnNode("."))
                addModNewInstructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/String",
                        "replace",
                        "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
                        false
                    )
                )
                // String.replace('\\', '.')
                addModNewInstructions.add(LdcInsnNode("\\"))
                addModNewInstructions.add(LdcInsnNode("."))
                addModNewInstructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/String",
                        "replace",
                        "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
                        false
                    )
                )
            }

            addModNewInstructions.add(insn)
        }

        addMod.instructions = addModNewInstructions
    }

    class ClassWriterASM(val fileSystem: FileSystem): ClassWriter(COMPUTE_MAXS or COMPUTE_FRAMES) {
        override fun getCommonSuperClass(type1: String, type2: String): String {
            return try {
                super.getCommonSuperClass(type1, type2)
            } catch (_: Exception) {
                // one of the classes was not found, so we now need to calculate it
                val it1 = buildInheritanceTree(type1)
                val it2 = buildInheritanceTree(type2)
                val common = it1.intersect(it2.toSet())
                common.first()
            }
        }

        fun buildInheritanceTree(type1: String): List<String> {
            val tree = mutableListOf<String>()
            var current = type1
            while (current != "java/lang/Object") {
                tree.add(current)
                val currentClassFile = fileSystem.getPath("/${current}.class")
                current = if (!currentClassFile.exists()) {
                    "java/lang/Object"
                } else {
                    val classReader = ClassReader(currentClassFile.readBytes())
                    classReader.superName
                }
            }
            tree.add("java/lang/Object")
            return tree
        }
    }
}