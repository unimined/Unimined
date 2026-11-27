package xyz.wagyourtail.unimined.transform.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.FG2DevFixes
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2
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

    @Test
    fun `getDirectories skips absent malformed and prefix-colliding MOD_CLASSES members`() {
        val source = Files.createDirectories(dir.resolve("classes/java/main")).toFile()
        val resources = Files.createDirectories(dir.resolve("resources/main")).toFile()
        val absent = dir.resolve("resources/absent").toFile()
        val collision = Files.createDirectories(dir.resolve("resources/collision")).toFile()
        val sep = java.io.File.pathSeparator
        val serialized = listOf(
            "omnilook%%${resources.absolutePath}",
            "omnilook%%${absent.absolutePath}",
            "omnilook%%${source.absolutePath}",
            "omnilook2%%${collision.absolutePath}",
            "malformed",
        ).joinToString(sep)

        assertEquals(
            listOf(resources, source),
            UniminedFg2.getDirectories(source, serialized).toList(),
            "only existing dirs from the exact label group may be returned"
        )
    }

    @Test
    fun `deobfRemapper replaces findAndMergeSuperMaps with dev-name lookup`() {
        val jar = dir.resolve("deobf.jar")
        val remapper =
            "cpw/mods/fml/common/asm/transformers/deobf/FMLDeobfuscatingRemapper"
        writeJar(
            jar, mapOf(
                "$remapper.class" to deobfRemapper(
                    remapper, "cpw/mods/fml/common/patcher/ClassPatchManager"
                ),
            )
        )
        FileSystems.newFileSystem(jar).use { FG2DevFixes.fix(it) }
        FileSystems.newFileSystem(jar).use { fs ->
            val node = node(fs.getPath("$remapper.class"))
            val method = node.methods.single { it.name == "findAndMergeSuperMaps" }
            val insns = method.instructions.toArray().toList()
            // head inject: runtimeDeobf gate first
            assertTrue(insns.any {
                it is LdcInsnNode && it.cst == "unimined.fg2.runtimeDeobf"
            }, "runtimeDeobf gate missing")
            // dev jar byte reads: srg name then mapped-name fallback
            assertTrue(insns.count {
                it is MethodInsnNode && it.name == "getClassBytes"
            } >= 2, "getClassBytes reads missing")
            // super/interface names unmapped back to srg before merge
            assertTrue(insns.any {
                it is MethodInsnNode && it.name == "unmap"
            }, "unmap call missing")
            assertTrue(insns.any {
                it is MethodInsnNode && it.name == "mergeSuperMaps"
            }, "mergeSuperMaps call missing")
            // the stock body stays reachable under runtimeDeobf
            // (the gate returns without cancelling)
            assertTrue(insns.any {
                it is MethodInsnNode && it.name == "getPatchedResource"
            }, "stock getPatchedResource path removed")
        }
    }

    @Test
    fun `deobfuscationTransformer class-name methods get the dev-namespace collision guard`() {
        val jar = dir.resolve("deobfTx.jar")
        val tx = "net/minecraftforge/fml/common/asm/transformers/DeobfuscationTransformer"
        writeJar(
            jar, mapOf(
                "$tx.class" to stubClass(
                    tx,
                    Triple(
                        "unmapClassName", "(Ljava/lang/String;)Ljava/lang/String;",
                        Opcodes.ACC_PUBLIC
                    ),
                    Triple(
                        "remapClassName", "(Ljava/lang/String;)Ljava/lang/String;",
                        Opcodes.ACC_PUBLIC
                    ),
                ),
            )
        )
        FileSystems.newFileSystem(jar).use { FG2DevFixes.fix(it) }
        FileSystems.newFileSystem(jar).use { fs ->
            val node = node(fs.getPath("$tx.class"))
            // both directions carry the guard: unmap inverse-hits dev
            // names back to srg; remap forward-hits dev names that
            // collide with an srg key (net/minecraft/potion/Potion ->
            // entity/living/effect/StatusEffect misdefine)
            for (methodName in listOf("unmapClassName", "remapClassName")) {
                val insns = node.methods.single { it.name == methodName }
                    .instructions.toArray().toList()
                // runtimeDeobf gate first — stock inverse mapping survives
                // under it for srg-runtime launches
                assertTrue(insns.any {
                    it is LdcInsnNode && it.cst == "unimined.fg2.runtimeDeobf"
                }, "$methodName: runtimeDeobf gate missing")
                // dev jar ground truth: resource-exists probe on the input
                assertTrue(insns.any {
                    it is MethodInsnNode && it.name == "getResource"
                }, "$methodName: resource-exists probe missing")
                // srg-probe miss path: forward lookup through the remapper,
                // resolved reflectively through the launch loader
                assertTrue(insns.any {
                    it is MethodInsnNode && it.name == "loadClass"
                }, "$methodName: reflective remapper resolution missing")
                assertTrue(insns.any {
                    it is MethodInsnNode && it.name == "invoke"
                }, "$methodName: forward map invocation missing")
            }
        }
    }

    @Test
    fun `fmlTweaker preloads ClassLoader$1 before setSecurityManager`() {
        val jar = dir.resolve("tweaker.jar")
        val tweaker = "net/minecraftforge/fml/common/launcher/FMLTweaker"
        writeJar(
            jar, mapOf(
                "$tweaker.class" to fmlTweaker(tweaker),
            )
        )
        FileSystems.newFileSystem(jar).use { FG2DevFixes.fix(it) }
        FileSystems.newFileSystem(jar).use { fs ->
            val node = node(fs.getPath("$tweaker.class"))
            val insns = node.methods.single { it.name == "<init>" }
                .instructions.toArray().toList()
            val urlIdx = insns.indexOfFirst {
                it is MethodInsnNode && it.name == "injectModClassUrlsIntoLaunch"
            }
            val preloadIdx = insns.indexOfFirst {
                it is MethodInsnNode && it.name == "preloadSmRaceGuard"
            }
            val setSmIdx = insns.indexOfFirst {
                it is MethodInsnNode && it.name == "setSecurityManager"
            }
            assertTrue(urlIdx >= 0, "constructor MOD_CLASSES inject missing")
            assertTrue(preloadIdx >= 0, "preloadSmRaceGuard inject missing")
            assertTrue(
                urlIdx in 0 until preloadIdx,
                "constructor MOD_CLASSES injection must precede the security preload"
            )
            assertTrue(
                preloadIdx in 0 until setSmIdx,
                "preload not ahead of setSecurityManager"
            )
        }
    }

    @Test
    fun `cleanroom fmlTweaker injects MOD_CLASSES before mixin bootstrap`() {
        val jar = dir.resolve("tweaker-cleanroom.jar")
        val tweaker = "net/minecraftforge/fml/common/launcher/FMLTweaker"
        writeJar(
            jar, mapOf(
                "$tweaker.class" to fmlTweaker(tweaker, securityManager = false),
            )
        )
        FileSystems.newFileSystem(jar).use { FG2DevFixes.fix(it) }
        FileSystems.newFileSystem(jar).use { fs ->
            val node = node(fs.getPath("$tweaker.class"))
            val insns = node.methods.single { it.name == "<init>" }
                .instructions.toArray().toList()
            val urlIdx = insns.indexOfFirst {
                it is MethodInsnNode && it.name == "injectModClassUrlsIntoLaunch"
            }
            val mixinIdx = insns.indexOfFirst {
                it is MethodInsnNode && it.owner ==
                        "org/spongepowered/asm/launch/MixinBootstrap" &&
                        it.name == "init"
            }
            assertTrue(urlIdx >= 0, "constructor MOD_CLASSES inject missing")
            assertTrue(mixinIdx >= 0, "synthetic MixinBootstrap anchor missing")
            assertTrue(
                urlIdx in 0 until mixinIdx,
                "MOD_CLASSES injection must precede MixinBootstrap.init"
            )
        }
    }

    @Test
    fun `tweakers get MOD_CLASSES url injection at injectIntoClassLoader head`() {
        for ((entry, self) in listOf(
            "net/minecraftforge/fml/common/launcher/FMLTweaker" to
                    "net/minecraftforge/fml/common/launcher/FMLTweaker",
            "cpw/mods/fml/common/launcher/FMLServerTweaker" to
                    "cpw/mods/fml/common/launcher/FMLServerTweaker",
        )) {
            val jar = dir.resolve("tweaker-${self.substringAfterLast('/')}2.jar")
            writeJar(
                jar, mapOf(
                    "$entry.class" to tweakerWithInject(self),
                )
            )
            FileSystems.newFileSystem(jar).use { FG2DevFixes.fix(it) }
            FileSystems.newFileSystem(jar).use { fs ->
                val node = node(fs.getPath("$entry.class"))
                val method = node.methods.single {
                    it.name == "injectIntoClassLoader"
                }
                val insns = method.instructions.toArray().toList()
                val injectIdx = insns.indexOfFirst {
                    it is MethodInsnNode && it.name == "injectModClassUrls"
                }
                assertTrue(injectIdx >= 0, "$self: injectModClassUrls missing")
                // HEAD: nothing but the pre-existing body instructions may
                // precede it — the synthetic body's first insn is a RETURN,
                // so the injection must come first
                val retIdx = insns.indexOfFirst {
                    it.opcode == Opcodes.RETURN
                }
                assertTrue(
                    injectIdx < retIdx,
                    "$self: injection not at head"
                )
            }
        }
    }

    @Test
    fun `mod discoverer gets MOD_CLASSES candidate epilogue`() {
        val jar = dir.resolve("mod-discoverer.jar")
        val disc = "net/minecraftforge/fml/common/discovery/ModDiscoverer"
        writeJar(
            jar, mapOf(
                "$disc.class" to modDiscoverer(disc),
            )
        )
        FileSystems.newFileSystem(jar).use { FG2DevFixes.fix(it) }
        FileSystems.newFileSystem(jar).use { fs ->
            val node = node(fs.getPath("$disc.class"))
            val method = node.methods.single {
                it.name == "findClasspathMods"
            }
            val insns = method.instructions.toArray().toList()
            val injectIdx = insns.indexOfFirst {
                it is MethodInsnNode &&
                        it.name == "injectModClassCandidates"
            }
            assertTrue(
                injectIdx >= 0,
                "injectModClassCandidates epilogue missing"
            )
            // epilogue: the injection follows the original body's RETURN-less
            // fall-through — with the void synthetic body, the only RETURN
            // is the epilogue's own, so the call must precede it
            assertTrue(insns.any {
                it is MethodInsnNode && it.owner.endsWith("UniminedFg2")
            }, "helper invocation missing")
        }
    }

    @Test
    fun `injectModClassCandidates uses the protected resource-aware constructor when present`() {
        val resources = Files.createDirectories(dir.resolve("resources/main")).toFile()
        Files.write(resources.toPath().resolve("mcmod.info"), "[]".toByteArray())
        val classes = Files.createDirectories(dir.resolve("classes/java/main")).toFile()
        val (loader, discoverer) = discoveryFixture(includeTwoArg = true)
        val modCandidate = loader.loadClass(
            "net.minecraftforge.fml.common.discovery.ModCandidate")
        @Suppress("UNCHECKED_CAST")
        val candidates = discoverer.javaClass.getDeclaredField("candidates")
            .apply { isAccessible = true }.get(discoverer) as MutableList<Any>
        // cleanroom's native consecutive pairing claims resources/main as
        // the scan root. The supplement must still add classes/java/main:
        // its mod-container identity differs under the correct constructor.
        val native = modCandidate.getDeclaredConstructor(
            java.io.File::class.java, java.io.File::class.java)
            .apply { isAccessible = true }
            .newInstance(resources, classes)
        candidates.add(native)

        UniminedFg2.injectModClassCandidates(
            discoverer,
            "omnilook%%${resources.absolutePath}" +
                    java.io.File.pathSeparator +
                    "omnilook%%${classes.absolutePath}"
        )

        assertEquals(2, candidates.size,
            "the correct class-root candidate must survive dedup")
        val supplement = candidates[1]
        fun getter(name: String) = supplement.javaClass
            .getMethod(name).invoke(supplement)
        assertEquals(classes, getter("getClassPathRoot"))
        assertEquals(resources, getter("getResourcePathRoot"))
        assertEquals(classes, getter("getModContainer"))
    }

    @Test
    fun `injectModClassCandidates five-arg fallback keeps the class root as candidate identity`() {
        val resources = Files.createDirectories(dir.resolve("resources/main")).toFile()
        Files.write(resources.toPath().resolve("mcmod.info"), "[]".toByteArray())
        val classes = Files.createDirectories(dir.resolve("classes/java/main")).toFile()
        val (_, discoverer) = discoveryFixture(includeTwoArg = false)

        UniminedFg2.injectModClassCandidates(
            discoverer,
            "omnilook%%${resources.absolutePath}" +
                    java.io.File.pathSeparator +
                    "omnilook%%${classes.absolutePath}"
        )

        @Suppress("UNCHECKED_CAST")
        val candidates = discoverer.javaClass.getDeclaredField("candidates")
            .apply { isAccessible = true }.get(discoverer) as MutableList<Any>
        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        fun getter(name: String) = candidate.javaClass
            .getMethod(name).invoke(candidate)
        assertEquals(classes, getter("getClassPathRoot"))
        assertEquals(classes, getter("getResourcePathRoot"),
            "the five-arg constructor cannot carry a distinct resource root")
        assertEquals(classes, getter("getModContainer"),
            "the resource root must not become the dedup identity")
    }

    @Test
    fun `modClassCandidatePairs pairs era class dirs with the metadata root`() {
        // the fixture shape: resources/main carries mcmod.info, the era
        // source set has no resources output of its own, nonexistent
        // members drop out, the manifest member pairs like any other
        val resourcesMain = Files.createDirectories(
            dir.resolve("build/resources/main")).toFile()
        Files.write(resourcesMain.toPath().resolve("mcmod.info"),
            "[]".toByteArray())
        val classesMain = Files.createDirectories(
            dir.resolve("build/classes/java/main")).toFile()
        val classesEra = Files.createDirectories(
            dir.resolve("build/classes/java/lexforge12")).toFile()
        val manifestMember = Files.createDirectories(
            dir.resolve("build/mod-classes/_env")).toFile()
        val absent = dir.resolve("build/resources/lexforge12").toFile()
        val sep = java.io.File.pathSeparator
        val serialized = listOf(
            "omnilook%%${resourcesMain.absolutePath}",
            "omnilook%%${classesMain.absolutePath}",
            "omnilook%%${classesEra.absolutePath}",
            "omnilook%%${absent.absolutePath}",
            "omnilook%%${manifestMember.absolutePath}",
        ).joinToString(sep)

        val pairs = UniminedFg2.modClassCandidatePairs(serialized)
        assertEquals(
            mapOf(
                classesMain to resourcesMain,
                classesEra to resourcesMain,
                manifestMember to resourcesMain,
            ),
            pairs,
            "every existing non-root member pairs with the " +
                "mcmod.info-bearing root; the root itself is not scanned"
        )
    }

    @Test
    fun `modClassCandidatePairs falls back to first member and handles single-dir groups`() {
        val only = Files.createDirectories(dir.resolve("only")).toFile()
        val a = Files.createDirectories(dir.resolve("a")).toFile()
        val b = Files.createDirectories(dir.resolve("b")).toFile()
        val sep = java.io.File.pathSeparator
        val serialized = listOf(
            "solo%%${only.absolutePath}",
            "duo%%${a.absolutePath}",
            "duo%%${b.absolutePath}",
        ).joinToString(sep)

        val pairs = UniminedFg2.modClassCandidatePairs(serialized)
        assertEquals(
            mapOf(
                only to only,
                b to a,
            ),
            pairs,
            "no mcmod.info anywhere: root falls back to the first " +
                "member; a single-dir group pairs with itself"
        )
    }

    @Test
    fun `injectModClassUrls appends existing deduped members only`() {
        val present = Files.createDirectories(dir.resolve("present")).toFile()
        val absent = dir.resolve("absent").toFile()
        val sep = java.io.File.pathSeparator
        val serialized = listOf(
            "omnilook%%${present.absolutePath}",
            "omnilook%%${absent.absolutePath}",
            "omnilook%%${present.absolutePath}",
            "malformed",
        ).joinToString(sep)

        val loader = object : java.net.URLClassLoader(arrayOf()) {
            public override fun addURL(url: java.net.URL?) {
                super.addURL(url)
            }
        }
        UniminedFg2.injectModClassUrls(loader, serialized)
        assertEquals(
            listOf(present.toURI().toURL()),
            loader.getURLs().toList(),
            "only the existing member lands, once"
        )
    }

    /**
     * Mirrors the FG2-era tweakers'
     * {@code injectIntoClassLoader(LaunchClassLoader)} — an empty void
     * body the HEAD injection must precede.
     */
    private fun tweakerWithInject(self: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V17, Opcodes.ACC_PUBLIC, self, null,
            "java/lang/Object", null
        )
        cw.visitMethod(
            Opcodes.ACC_PUBLIC, "injectIntoClassLoader",
            "(Lnet/minecraft/launchwrapper/LaunchClassLoader;)V",
            null, null
        ).apply {
            visitCode()
            insn(Opcodes.RETURN)
            visitMaxs(0, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * Mirrors FML's {@code findClasspathMods(ModClassLoader)} —
     * empty void body.
     */
    private fun modDiscoverer(self: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V17, Opcodes.ACC_PUBLIC, self, null,
            "java/lang/Object", null
        )
        cw.visitMethod(
            Opcodes.ACC_PUBLIC, "findClasspathMods",
            "(Lnet/minecraftforge/fml/common/ModClassLoader;)V",
            null, null
        ).apply {
            visitCode()
            insn(Opcodes.RETURN)
            visitMaxs(0, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
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

    // ---- runtime ModCandidate constructor harness ----------------------

    /** Dedup semantics mirror ModDiscoverer#addCandidate. */
    object ModCandidateHarness {
        @JvmStatic
        fun addCandidateDedup(candidates: MutableList<Any>, candidate: Any) {
            val container = candidate.javaClass
                .getMethod("getModContainer").invoke(candidate)
            val duplicate = candidates.any {
                it.javaClass.getMethod("getModContainer").invoke(it) == container
            }
            if (!duplicate) candidates.add(candidate)
        }
    }

    private class BytesClassLoader(
        private val classes: Map<String, ByteArray>
    ) : ClassLoader(FG2DevFixesTest::class.java.classLoader) {
        override fun findClass(name: String): Class<*> {
            val bytes = classes[name] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }
    }

    private fun discoveryFixture(includeTwoArg: Boolean): Pair<ClassLoader, Any> {
        val candidate = "net.minecraftforge.fml.common.discovery.ModCandidate"
        val container = "net.minecraftforge.fml.common.discovery.ContainerType"
        val discoverer = "test.TestDiscoverer"
        val loader = BytesClassLoader(mapOf(
            candidate to modCandidateBytes(includeTwoArg),
            container to containerTypeBytes(),
            discoverer to discovererBytes(),
        ))
        return loader to loader.loadClass(discoverer)
            .getDeclaredConstructor().newInstance()
    }

    private fun containerTypeBytes(): ByteArray {
        val owner = "net/minecraftforge/fml/common/discovery/ContainerType"
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null,
            "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC,
            "DIR", "L$owner;", null, null).visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun modCandidateBytes(includeTwoArg: Boolean): ByteArray {
        val owner = "net/minecraftforge/fml/common/discovery/ModCandidate"
        val container = "net/minecraftforge/fml/common/discovery/ContainerType"
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null,
            "java/lang/Object", null)
        for (field in listOf("classPathRoot", "resourcePathRoot", "modContainer")) {
            cw.visitField(Opcodes.ACC_PRIVATE, field, "Ljava/io/File;",
                null, null).visitEnd()
        }

        fun ctor(access: Int, desc: String, locals: Int,
                 resourceArg: Int, containerArg: Int) {
            cw.visitMethod(access, "<init>", desc, null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object",
                    "<init>", "()V", false)
                fun assign(field: String, arg: Int) {
                    visitVarInsn(Opcodes.ALOAD, 0)
                    visitVarInsn(Opcodes.ALOAD, arg)
                    visitFieldInsn(Opcodes.PUTFIELD, owner, field,
                        "Ljava/io/File;")
                }
                assign("classPathRoot", 1)
                assign("resourcePathRoot", resourceArg)
                assign("modContainer", containerArg)
                visitInsn(Opcodes.RETURN)
                visitMaxs(2, locals)
                visitEnd()
            }
        }
        ctor(Opcodes.ACC_PUBLIC,
            "(Ljava/io/File;Ljava/io/File;L$container;ZZ)V",
            6, resourceArg = 1, containerArg = 2)
        if (includeTwoArg) {
            ctor(Opcodes.ACC_PROTECTED,
                "(Ljava/io/File;Ljava/io/File;)V",
                3, resourceArg = 2, containerArg = 1)
        }
        for ((method, field) in listOf(
            "getClassPathRoot" to "classPathRoot",
            "getResourcePathRoot" to "resourcePathRoot",
            "getModContainer" to "modContainer",
        )) {
            cw.visitMethod(Opcodes.ACC_PUBLIC, method,
                "()Ljava/io/File;", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitFieldInsn(Opcodes.GETFIELD, owner, field,
                    "Ljava/io/File;")
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun discovererBytes(): ByteArray {
        val owner = "test/TestDiscoverer"
        val candidate = "net/minecraftforge/fml/common/discovery/ModCandidate"
        val harness = "xyz/wagyourtail/unimined/transform/forge/" +
                "FG2DevFixesTest\$ModCandidateHarness"
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null,
            "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "candidates",
            "Ljava/util/List;", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object",
                "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitTypeInsn(Opcodes.NEW, "java/util/ArrayList")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList",
                "<init>", "()V", false)
            visitFieldInsn(Opcodes.PUTFIELD, owner, "candidates",
                "Ljava/util/List;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(3, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "addCandidate",
            "(L$candidate;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, owner, "candidates",
                "Ljava/util/List;")
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKESTATIC, harness,
                "addCandidateDedup", "(Ljava/util/List;Ljava/lang/Object;)V",
                false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 2)
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

    /**
     * Mirrors FML's `FMLTweaker.<init>`: super() then
     * {@code System.setSecurityManager(new FMLSecurityManager())}.
     */
    private fun fmlTweaker(self: String, securityManager: Boolean = true): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V17, Opcodes.ACC_PUBLIC, self, null,
            "java/lang/Object", null
        )
        cw.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "()V", null, null
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object",
                "<init>", "()V", false
            )
            if (securityManager) {
                visitTypeInsn(
                    Opcodes.NEW,
                    "net/minecraftforge/fml/relauncher/FMLSecurityManager"
                )
                insn(Opcodes.DUP)
                visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "net/minecraftforge/fml/relauncher/FMLSecurityManager",
                    "<init>", "()V", false
                )
                visitMethodInsn(
                    Opcodes.INVOKESTATIC, "java/lang/System",
                    "setSecurityManager",
                    "(Ljava/lang/SecurityManager;)V", false
                )
            } else {
                // cleanroom shape: no security-manager anchor, direct
                // mixin bootstrap in the constructor
                visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/spongepowered/asm/launch/MixinBootstrap",
                    "init", "()V", false
                )
            }
            insn(Opcodes.RETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * Mirrors FML's `findAndMergeSuperMaps`: getPatchedResource for
     * the class bytes, then mergeSuperMaps(name, cr.getSuperName(),
     * cr.getInterfaces()).
     */
    private fun deobfRemapper(self: String, patchManager: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V17, Opcodes.ACC_PUBLIC, self, null,
            "java/lang/Object", null
        )
        cw.visitField(
            Opcodes.ACC_PUBLIC, "classLoader",
            "Lnet/minecraft/launchwrapper/LaunchClassLoader;", null, null
        )
        cw.visitMethod(
            Opcodes.ACC_PUBLIC, "map",
            "(Ljava/lang/String;)Ljava/lang/String;", null, null
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            insn(Opcodes.ARETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC, "unmap",
            "(Ljava/lang/String;)Ljava/lang/String;", null, null
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            insn(Opcodes.ARETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC, "mergeSuperMaps",
            "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V",
            null, null
        ).apply {
            visitCode()
            insn(Opcodes.RETURN)
            visitMaxs(0, 4)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PRIVATE, "findAndMergeSuperMaps",
            "(Ljava/lang/String;)V", null, null
        ).apply {
            visitCode()
            // ClassPatchManager.INSTANCE.getPatchedResource(name, map(name), classLoader)
            visitFieldInsn(
                Opcodes.GETSTATIC, patchManager, "INSTANCE",
                "L$patchManager;"
            )
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, self, "map",
                "(Ljava/lang/String;)Ljava/lang/String;", false
            )
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(
                Opcodes.GETFIELD, self, "classLoader",
                "Lnet/minecraft/launchwrapper/LaunchClassLoader;"
            )
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, patchManager, "getPatchedResource",
                "(Ljava/lang/String;Ljava/lang/String;Lnet/minecraft/launchwrapper/LaunchClassLoader;)[B",
                false
            )
            visitVarInsn(Opcodes.ASTORE, 2)
            // ClassReader cr = new ClassReader(classBytes)
            visitTypeInsn(Opcodes.NEW, "org/objectweb/asm/ClassReader")
            insn(Opcodes.DUP)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(
                Opcodes.INVOKESPECIAL, "org/objectweb/asm/ClassReader",
                "<init>", "([B)V", false
            )
            visitVarInsn(Opcodes.ASTORE, 3)
            // mergeSuperMaps(name, cr.getSuperName(), cr.getInterfaces())
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ALOAD, 3)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "org/objectweb/asm/ClassReader",
                "getSuperName", "()Ljava/lang/String;", false
            )
            visitVarInsn(Opcodes.ALOAD, 3)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "org/objectweb/asm/ClassReader",
                "getInterfaces", "()[Ljava/lang/String;", false
            )
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, self, "mergeSuperMaps",
                "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V",
                false
            )
            insn(Opcodes.RETURN)
            visitMaxs(5, 4)
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
