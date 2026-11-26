package xyz.wagyourtail.unimined.mod

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.DeclaredAccessFile
import xyz.wagyourtail.unimined.mod.task.GenerateModMetadataTask
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Env-declared access files convert into the loader's consumed format
 * at `processResources` — the resource-shaping half of the 1.x
 * `aw2at`/`at2aw` helpers, identity-remap safe.
 */
class GenerateModMetadataTaskTest {

    @TempDir
    lateinit var dir: Path

    private val meta = ModMetadata(
        id = "testmod", name = null, version = null,
        description = null, authors = emptyList(), license = null,
        url = null, icon = null, environment = null,
        depends = emptyMap(), sections = emptyMap()
    )

    private val aw = """
        accessWidener v1 named
        accessible class example/Target
        accessible method example/Target run ()V
    """.trimIndent()

    private val at = "public example.Target\n"

    private fun task(
        loader: String,
        declared: List<Path>,
        resources: Path? = null
    ): GenerateModMetadataTask {
        val project = ProjectBuilder.builder()
            .withProjectDir(dir.toFile()).build()
        val t = project.tasks.register(
            "genModMetadata", GenerateModMetadataTask::class.java).get()
        t.metadata.set(meta)
        t.mixinConfigs.set(emptyList())
        t.loaderName.set(loader)
        t.environmentId.set("main")
        t.auxEnv.set(false)
        t.minecraftVersion.set("1.20.1")
        t.side.set("CLIENT")
        t.devNamespace.set("named")
        t.prodNamespaces.set(listOf("official"))
        resources?.let { t.resourceDirs.from(it.toFile()) }
        t.declaredAccessFiles.from(declared.map { it.toFile() })
        t.outputDir.set(dir.resolve("gen").toFile())
        t.manifestFile.set(dir.resolve("gen.mf").toFile())
        t.mergedPathsFile.set(dir.resolve("gen.merged.txt").toFile())
        return t
    }

    private fun write(rel: String, content: String, under: Path): Path {
        val f = under.resolve(rel)
        Files.createDirectories(f.parent)
        Files.write(f, content.toByteArray())
        return f
    }

    private fun manifestAttributes(path: Path): java.util.jar.Attributes =
        Files.newInputStream(path).use {
            java.util.jar.Manifest(it).mainAttributes
        }

    @Test
    fun `declared AW emits canonical widener and fmj declaration`() {
        val res = dir.resolve("resources")
        val authored = write("authored.accesswidener", aw, res)
        val t = task("fabric", listOf(authored), res)
        t.generate()

        val out = dir.resolve("gen")
        val canon = out.resolve("testmod.accesswidener")
        assertTrue(Files.isRegularFile(canon))
        val text = canon.readText()
        assertTrue(text.startsWith("accessWidener"))
        assertTrue(text.contains("example/Target"))
        // authored source excluded so only the canonical copy ships
        assertTrue(dir.resolve("gen.merged.txt").readText()
            .contains(authored.toAbsolutePath().toString()))
        // declared into generated fmj
        val fmj = out.resolve("fabric.mod.json").readText()
        assertTrue(fmj.contains("\"accessWidener\""))
        assertTrue(fmj.contains("testmod.accesswidener"))
    }

    @Test
    fun `emitted AW header collapses internal dev ns to named`() {
        // dev column `mojmap` is an internal name — the emitted
        // loader-facing header must read `named` (canonical role)
        val res = dir.resolve("resources")
        val authored = write("authored.accesswidener",
            "accessWidener\tv1\tnamed\naccessible\tclass\texample/Target\n",
            res)
        val t = task("fabric", listOf(authored), res)
        t.devNamespace.set("mojmap")
        t.namedNamespaces.set(setOf("mojmap"))
        t.generate()

        val text = dir.resolve("gen/testmod.accesswidener").readText()
        assertTrue(text.startsWith("accessWidener\tv1\tnamed"),
            "expected canonical named header, got: $text")
        assertTrue(text.contains("example/Target"))
    }

    @Test
    fun `AW converts to AT at the forge canonical path`() {
        val authored = write("mod.accesswidener", aw, dir.resolve("src"))
        val t = task("testforge", listOf(authored))
        t.generate()

        val canon = dir.resolve("gen/META-INF/accesstransformer.cfg")
        assertTrue(Files.isRegularFile(canon))
        val text = canon.readText()
        assertTrue(text.contains("example.Target"))
        assertTrue(text.contains("public"))
        // method row keeps its desc
        assertTrue(text.contains("run()V"))
    }

    @Test
    fun `manual file at the canonical path folds into the conversion`() {
        val res = dir.resolve("resources")
        val manual = write("testmod.accesswidener",
            "accessWidener v1 named\naccessible class example/Manual\n", res)
        val declared = write("other.accesswidener",
            "accessWidener v1 named\naccessible class example/Declared\n",
            dir.resolve("src"))
        val t = task("fabric", listOf(declared), res)
        t.generate()

        val canon = dir.resolve("gen/testmod.accesswidener")
        val text = canon.readText()
        assertTrue(text.contains("example/Manual"))
        assertTrue(text.contains("example/Declared"))
        val merged = dir.resolve("gen.merged.txt").readText()
        assertTrue(merged.contains(manual.toAbsolutePath().toString()))
        assertTrue(merged.contains(declared.toAbsolutePath().toString()))
    }

    @Test
    fun `AT input on an AW loader without a mapping bundle fails clearly`() {
        val authored = write("accesstransformer.cfg", at, dir.resolve("src"))
        val t = task("fabric", listOf(authored))
        val e = assertThrows(GradleException::class.java) { t.generate() }
        assertTrue(e.message!!.contains("accessTransformers"))
    }

    @Test
    fun `fabric emitter folds loader version and manifest attrs into the merge file`() {
        val project = ProjectBuilder.builder()
            .withProjectDir(dir.toFile()).build()
        val t = project.tasks.register(
            "genModMetadata", GenerateModMetadataTask::class.java).get()
        t.metadata.set(meta)
        t.mixinConfigs.set(emptyList())
        t.loaderName.set("fabric")
        t.environmentId.set("main")
        t.auxEnv.set(false)
        t.minecraftVersion.set("1.20.1")
        t.side.set("CLIENT")
        t.devNamespace.set("named")
        t.prodNamespaces.set(listOf("official"))
        t.loaderVersion.set("0.16.0")
        t.manifestAttributes.set(
            mapOf("Calamus-Generation" to "2"))
        t.outputDir.set(dir.resolve("gen").toFile())
        t.manifestFile.set(dir.resolve("gen.mf").toFile())
        t.mergedPathsFile.set(dir.resolve("gen.merged.txt").toFile())
        t.generate()

        val mf = dir.resolve("gen.mf").readText()
        assertTrue(mf.contains("Manifest-Version: 1.0"))
        assertTrue(mf.contains("Fabric-Minecraft-Version: 1.20.1"))
        assertTrue(mf.contains("Fabric-Loader-Version: 0.16.0"))
        assertTrue(mf.contains("Calamus-Generation: 2"))
        // shipped-jar namespace + unimined provenance stamps
        assertTrue(mf.contains("Fabric-Mapping-Namespace: official"),
            "prod ns stamps as the mapping namespace:\n$mf")
        assertTrue(mf.contains("Unimined-Version: "),
            "provenance marker stamps from Implementation-Version:\n$mf")
    }

    @Test
    fun `emitted manifest also lands in generated resources for the dev classpath`() {
        // exploded-dir FML scan reads META-INF/MANIFEST.MF inside
        // classpath directories — the manifest body must reach the dev
        // output dir, not only the jar's manifest merge
        val project = ProjectBuilder.builder()
            .withProjectDir(dir.toFile()).build()
        val t = project.tasks.register(
            "genModMetadata", GenerateModMetadataTask::class.java).get()
        t.metadata.set(meta)
        t.mixinConfigs.set(emptyList())
        t.loaderName.set("fabric")
        t.environmentId.set("main")
        t.auxEnv.set(false)
        t.minecraftVersion.set("1.20.1")
        t.side.set("CLIENT")
        t.devNamespace.set("named")
        t.prodNamespaces.set(listOf("official"))
        t.manifestAttributes.set(mapOf("FMLModType" to "LANGPROVIDER"))
        t.outputDir.set(dir.resolve("gen").toFile())
        t.manifestFile.set(dir.resolve("gen.mf").toFile())
        t.mergedPathsFile.set(dir.resolve("gen.merged.txt").toFile())
        t.generate()

        val devMf = dir.resolve("gen/META-INF/MANIFEST.MF")
        assertTrue(Files.isRegularFile(devMf),
            "manifest must reach the dev classpath output dir")
        assertTrue(devMf.readText().contains("FMLModType: LANGPROVIDER"))
        assertTrue(dir.resolve("gen.mf").readText()
            .contains("FMLModType: LANGPROVIDER"))
    }

    @Test
    fun `manual manifest resource merges generated attrs, generated wins`() {
        val res = dir.resolve("resources")
        val manual = write("META-INF/MANIFEST.MF",
            "Manifest-Version: 1.0\r\nManual-Attr: keep\r\nFMLModType: MOD\r\n\r\n", res)
        val project = ProjectBuilder.builder()
            .withProjectDir(dir.toFile()).build()
        val t = project.tasks.register(
            "genModMetadata", GenerateModMetadataTask::class.java).get()
        t.metadata.set(meta)
        t.mixinConfigs.set(emptyList())
        t.loaderName.set("fabric")
        t.environmentId.set("main")
        t.auxEnv.set(false)
        t.minecraftVersion.set("1.20.1")
        t.side.set("CLIENT")
        t.devNamespace.set("named")
        t.prodNamespaces.set(listOf("official"))
        t.manifestAttributes.set(mapOf("FMLModType" to "LANGPROVIDER"))
        t.resourceDirs.from(res.toFile())
        t.outputDir.set(dir.resolve("gen").toFile())
        t.manifestFile.set(dir.resolve("gen.mf").toFile())
        t.mergedPathsFile.set(dir.resolve("gen.merged.txt").toFile())
        t.generate()

        val devMf = dir.resolve("gen/META-INF/MANIFEST.MF").readText()
        assertTrue(devMf.contains("Manual-Attr: keep"),
            "manual attrs fold into the generated manifest:\n$devMf")
        assertTrue(devMf.contains("FMLModType: LANGPROVIDER"),
            "generated attrs win over manual:\n$devMf")
        assertTrue(devMf.contains("Unimined-Version:"),
            "marker survives the manual manifest merge:\n$devMf")
        assertTrue(dir.resolve("gen.merged.txt").readText()
            .contains(manual.toAbsolutePath().toString()))
    }

    @Test
    fun `accessFile override emits at custom path outside the canonical merge`() {
        val custom = write("custom_at.cfg", at, dir.resolve("src"))
        val declared = write("mod.accesswidener", aw, dir.resolve("src"))
        val t = task("testforge", listOf(declared))
        t.customAccessFiles.set(listOf(
            DeclaredAccessFile(
                custom.toAbsolutePath().toString(),
                format = "at",
                output = "META-INF/custom_at.cfg",
                declare = false
            )
        ))
        t.generate()

        // canonical merge produced its file; the override lands at
        // its own path rather than folding into it
        assertTrue(Files.isRegularFile(
            dir.resolve("gen/META-INF/accesstransformer.cfg")))
        val customOut = dir.resolve("gen/META-INF/custom_at.cfg")
        assertTrue(Files.isRegularFile(customOut))
        assertTrue(customOut.readText().contains("example.Target"))
    }

    @Test
    fun `AW declared on an AT loader needs no mapping bundle`() {
        // no accessBundle attached — AW→AT has no tree dependency
        val authored = write("mod.accesswidener", aw, dir.resolve("src"))
        val t = task("testforge", listOf(authored))
        t.generate()
        assertTrue(
            Files.isRegularFile(
                dir.resolve("gen/META-INF/accesstransformer.cfg")))
    }

    private val ct = """
        classTweaker v1 named
        accessible class example/Target
        inject-interface example/Target com/example/IFoo
    """.trimIndent()

    @Test
    fun `CT doc merges into canonical classTweaker on fabric 0_18`() {
        val authored = write("mod.classtweaker", ct, dir.resolve("src"))
        val t = task("fabric", listOf(authored))
        t.loaderVersion.set("0.18.0")
        t.generate()

        val canon = dir.resolve("gen/testmod.accesswidener")
        assertTrue(Files.isRegularFile(canon))
        val text = canon.readText()
        assertTrue(text.startsWith("classTweaker"))
        assertTrue(Regex("accessible\\s+class\\s+example/Target")
            .containsMatchIn(text))
        assertTrue(Regex(
            "inject-interface\\s+example/Target\\s+com/example/IFoo")
                .containsMatchIn(text))
        // the doc still declares through `accessWidener` — fmj v1's
        // only access field; 0.18 parses CT content through it
        assertTrue(dir.resolve("gen/fabric.mod.json").readText()
            .contains("\"accessWidener\""))
    }

    @Test
    fun `CT directives drop below fabric 0_18`() {
        val authored = write("mod.classtweaker", ct, dir.resolve("src"))
        val t = task("fabric", listOf(authored))
        t.loaderVersion.set("0.17.9")
        t.generate()

        val text = dir.resolve("gen/testmod.accesswidener").readText()
        assertTrue(text.startsWith("accessWidener"))
        assertTrue(text.contains("example/Target"))
        assertFalse(text.contains("inject-interface"))
    }

    @Test
    fun `CT doc on an AT loader keeps only access directives`() {
        val authored = write("mod.classtweaker", ct, dir.resolve("src"))
        val t = task("testforge", listOf(authored))
        t.generate()

        val text = dir.resolve("gen/META-INF/accesstransformer.cfg")
            .readText()
        assertTrue(text.contains("public example.Target"))
        assertFalse(text.contains("inject-interface"))
    }

    @Test
    fun `declared interface injections ride the loom key`() {
        val t = task("fabric", emptyList())
        t.declaredInterfaceInjections.set(
            mapOf("net/minecraft/Target" to listOf("com/example/IFoo")))
        t.generate()
        val fmj = dir.resolve("gen/fabric.mod.json").readText()
        assertTrue(fmj.contains("loom:injected_interfaces"))
        assertTrue(fmj.contains("com/example/IFoo"))
    }

    @Test
    fun `discoverMixinConfigs flag off skips resource configs`() {
        val res = dir.resolve("resources")
        write("testmod.mixins.json",
            """{"package": "com.example.mixin", "mixins": []}""", res)
        val t = task("fabric", emptyList(), res)
        t.discoverMixinConfigs.set(false)
        t.generate()
        assertFalse(dir.resolve("gen/fabric.mod.json").readText()
            .contains("testmod.mixins.json"))
    }

    @Test
    fun `accessPathOverride renames the canonical doc and declaration`() {
        val authored = write("authored.accesswidener", aw,
            dir.resolve("src"))
        val t = task("fabric", listOf(authored))
        t.accessPathOverride.set("custom.ct")
        t.generate()

        val canon = dir.resolve("gen/custom.ct")
        assertTrue(Files.isRegularFile(canon))
        assertTrue(canon.readText().contains("example/Target"))
        // nothing lands at the conventional path, and fmj declares the
        // custom name through `accessWidener`
        assertFalse(Files.exists(dir.resolve("gen/testmod.accesswidener")))
        assertTrue(dir.resolve("gen/fabric.mod.json").readText()
            .contains("custom.ct"))
    }

    @Test
    fun `accessPathOverride still expands modid`() {
        val authored = write("authored.accesswidener", aw,
            dir.resolve("src"))
        val t = task("fabric", listOf(authored))
        t.accessPathOverride.set("META-INF/{modid}.ct")
        t.generate()
        assertTrue(Files.isRegularFile(
            dir.resolve("gen/META-INF/testmod.ct")))
    }

    @Test
    fun `accessFormatOverride re-emits canonical doc in that format`() {
        val authored = write("authored.accesswidener", aw,
            dir.resolve("src"))
        val t = task("fabric", listOf(authored))
        t.accessFormatOverride.set("at")
        t.generate()

        // AW input converted to AT rows — no AW header, `public` rows
        val canon = dir.resolve("gen/testmod.accesswidener")
        assertTrue(Files.isRegularFile(canon))
        val text = canon.readText()
        assertFalse(text.contains("accessWidener"))
        // AT rows use Forge's dotted-class form
        assertTrue(text.contains("public example.Target"))
    }

    @Test
    fun `accessFormatOverride rejects unknown format names`() {
        val authored = write("authored.accesswidener", aw,
            dir.resolve("src"))
        val t = task("fabric", listOf(authored))
        t.accessFormatOverride.set("bogus")
        org.junit.jupiter.api.assertThrows<org.gradle.api.GradleException> {
            t.generate()
        }
    }

    /** Minimal compiled class bytes — [ifaces] implemented, optional
     *  `@Mixin` annotation carrying [mixinTargets]. */
    private fun scanClassBytes(
        internalName: String,
        ifaces: List<String>,
        mixinTargets: List<String>
    ): ByteArray {
        val cw = org.objectweb.asm.ClassWriter(0)
        cw.visit(
            org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC,
            internalName, null, "java/lang/Object", ifaces.toTypedArray()
        )
        if (mixinTargets.isNotEmpty()) {
            val av = cw.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/Mixin;", true)
            val arr = av.visitArray("value")
            mixinTargets.forEach {
                arr.visit(null, org.objectweb.asm.Type.getObjectType(it))
            }
            arr.visitEnd()
            av.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun scanFixture(): Path {
        val classes = dir.resolve("classes")
        val f = classes.resolve("com/example/mixin/MyMixin.class")
        Files.createDirectories(f.parent)
        Files.write(f, scanClassBytes(
            "com/example/mixin/MyMixin",
            ifaces = listOf("com/example/ExampleInterface"),
            mixinTargets = listOf("net/minecraft/class_161")
        ))
        return classes
    }

    private val noIdMeta = ModMetadata(
        id = null, name = null, version = null,
        description = null, authors = emptyList(), license = null,
        url = null, icon = null, environment = null,
        depends = emptyMap(), sections = emptyMap()
    )

    /** Positive control for the scan gate — with `mod.id` declared the
     *  scan runs and its rows merge into the canonical access doc
     *  (upstream #222). */
    @Test
    fun `interfaceInjectionScan rows reach the canonical doc when id declared`() {
        val res = dir.resolve("resources")
        write("testmod.mixins.json",
            """{"package": "com.example.mixin", "mixins": ["MyMixin"]}""", res)
        val t = task("fabric", emptyList(), res)
        t.classesDirs.from(scanFixture().toFile())
        t.interfaceInjectionScan.set(true)
        t.loaderVersion.set("0.18.0")
        t.generate()

        val canon = dir.resolve("gen/testmod.accesswidener")
        assertTrue(Files.isRegularFile(canon),
            "scan-derived CT rows must emit a canonical doc")
        val text = canon.readText()
        assertTrue(Regex("transitive-inject-interface\\s+" +
            "net/minecraft/class_161\\s+com/example/ExampleInterface")
            .containsMatchIn(text),
            "scan row missing from canonical doc:\n$text")
    }

    /** Issue #222 — without a `mod` id the scan's output channels
     *  have nothing to key on: the scan is skipped (with a warning)
     *  rather than silently emitting an unreferenced doc. */
    @Test
    fun `interfaceInjectionScan without mod id skips the scan`() {
        val res = dir.resolve("resources")
        write("testmod.mixins.json",
            """{"package": "com.example.mixin", "mixins": ["MyMixin"]}""", res)
        val t = task("fabric", emptyList(), res)
        t.metadata.set(noIdMeta)
        t.classesDirs.from(scanFixture().toFile())
        t.interfaceInjectionScan.set(true)
        t.loaderVersion.set("0.18.0")
        t.generate()

        // no canonical doc carries scan rows — the `{modid}` fallback
        // path must not appear either
        val out = dir.resolve("gen")
        val scanRow = Regex("transitive-inject-interface")
        val emitted = if (Files.isDirectory(out)) {
            Files.walk(out).use { s ->
                s.filter { Files.isRegularFile(it) }
                    .map { it.readText() }.toList()
            }
        } else emptyList()
        assertFalse(emitted.any { scanRow.containsMatchIn(it) },
            "scan rows must not emit without a mod id: $emitted")
    }

    @Test
    fun `emitLoaderMetadata false suppresses loader documents but keeps manifest attrs`() {
        val res = dir.resolve("resources")
        write("testmod.mixins.json",
            """{"package": "com.example.mixin", "mixins": []}""", res)
        val t = task("testmanifest", emptyList(), res)
        t.minecraftVersion.set("1.12.2")
        t.emitLoaderMetadata.set(false)
        t.generate()

        val out = dir.resolve("gen")
        assertFalse(Files.exists(out.resolve("testmod.manifestloader")),
            "loader descriptor must stay hand-authored when emission is off")
        val attrs = manifestAttributes(dir.resolve("gen.mf"))
        assertEquals("testmod.mixins.json", attrs.getValue("MixinConfigs"))
    }

    @Test
    fun `emitLoaderMetadata default emits loader documents`() {
        val t = task("testmanifest", emptyList())
        t.generate()

        assertTrue(Files.isRegularFile(
            dir.resolve("gen/testmod.manifestloader")))
    }

    @Test
    fun `dev manifest stamps a sanitized automatic module name`() {
        val res = dir.resolve("resources")
        write("testmod.mixins.json",
            """{"package": "com.example.mixin", "mixins": []}""", res)
        val t = task("testmanifest", emptyList(), res)
        t.environmentId.set("fabric-client")
        t.generate()

        val attrs = manifestAttributes(
            dir.resolve("gen/META-INF/MANIFEST.MF"))
        assertEquals("unimined.dev.fabric_client",
            attrs.getValue("Automatic-Module-Name"))
    }

    @Test
    fun `manual automatic module name wins over the dev manifest stamp`() {
        val res = dir.resolve("resources")
        write("META-INF/MANIFEST.MF",
            "Manifest-Version: 1.0\r\n" +
                    "Automatic-Module-Name: manual.module\r\n\r\n", res)
        write("testmod.mixins.json",
            """{"package": "com.example.mixin", "mixins": []}""", res)
        val t = task("testmanifest", emptyList(), res)
        t.generate()

        val attrs = manifestAttributes(
            dir.resolve("gen/META-INF/MANIFEST.MF"))
        assertEquals("manual.module",
            attrs.getValue("Automatic-Module-Name"))
    }

    @Test
    fun `files left in the output dir by an earlier run are removed`() {
        val stale = write("mod.accesswidener", aw, dir.resolve("gen"))
        task("fabric", emptyList()).generate()
        assertFalse(Files.exists(stale))
    }

}
