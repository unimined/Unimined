package xyz.wagyourtail.unimined.transform.forge.internal.userdev

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.api.util.ProgressGate
import xyz.wagyourtail.unimined.remap.spi.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass
import xyz.wagyourtail.unimined.transform.forge.internal.binpatch.BinpatchesPackPatcher
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

/**
 * `forge-binpatch` JAR-surface step — applies the userdev `binpatches`
 * archive (`joined.lzma`-format: lzma → flat zip of `.binpatch`
 * records) to the dev-Minecraft jar **in process** via
 * [BinpatchesPackPatcher] — no tool JVM spawn (the external
 * `binarypatcher` exec dominated the cold dev transform).
 *
 * Patches verify checksums against [cleanJar] — FG builds them
 * against the mcpconfig `rename`/`merge` output
 * (`net.minecraft:joined:<ver>:srg`), not the pipeline input.
 */
abstract class UserdevBinpatchStepSpec : DevArtifactOnlyStepSpec {
    @get:Input
    @get:Optional
    override val engine: String?
        get() = null


    /** The userdev jar — carries the lzma archive at [binpatchesKey].
     *  Optional: absent on non-Userdev profiles (spec passes the jar
     *  through unchanged). */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val installerJar: RegularFileProperty

    /** Entry key inside [installerJar] holding the lzma archive. */
    @get:Input
    @get:Optional
    abstract val binpatchesKey: Property<String>

    /**
     * The "clean" jar binpatches verify their checksums against —
     * FG builds them against `net.minecraft:joined:<ver>:srg` (the
     * mcpconfig `rename`/`merge` output), which is byte-stable but not
     * byte-identical to a tiny-remap of the merged jar. When absent
     * the pipeline input is used (non-Userdev passthrough).
     */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val cleanJar: RegularFileProperty
}

class UserdevBinpatchStepType : RemapStepType<UserdevBinpatchStepSpec> {

    override val name = "unimined"
    override val stepName = "forge-binpatch"
    override val specType: KClass<UserdevBinpatchStepSpec> = UserdevBinpatchStepSpec::class
    override val surfaces = setOf(RemapSurface.JAR)
    override val needsMappingTree = true

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: UserdevBinpatchStepSpec, ctx: StepContext) = ArtifactStep { input, c ->
        // non-Userdev environments contribute the spec with fields
        // absent — passthrough
        if (!spec.installerJar.isPresent || !spec.binpatchesKey.isPresent)
            return@ArtifactStep input
        val output = Files.createDirectories(c.workDir.resolve("forge-binpatch"))
            .resolve("patched.jar")
        // binpatches index the srg-renamed clean jar, not whatever
        // the pipeline produced — FG generates them from the
        // mcpconfig `rename`/`merge` output (FG6 MinecraftUserRepo:
        // `net.minecraft:joined:<ver>:srg`)
        val clean = spec.cleanJar.orNull?.asFile?.toPath() ?: input
        if (!Files.exists(output)) {
            val patches = java.util.jar.JarFile(spec.installerJar.get().asFile).use { jar ->
                val lzma = Files.createDirectories(c.workDir.resolve("forge-binpatch/in"))
                    .resolve("binpatches.lzma")
                jar.getInputStream(jar.getJarEntry(spec.binpatchesKey.get()))
                    .use {
                        Files.copy(
                            it, lzma,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                BinpatchesPackPatcher.readZipPatches(lzma)
            }
            val slog = c.log.subLogger("forge-binpatch")
            slog.info("read ${patches.size} joined binpatches")
            val total = AtomicLong()
            val gate = ProgressGate { elapsed, done ->
                slog.info {
                    "binpatching ${clean.fileName}: $done/${total.get()} entries in ${elapsed}ms"
                }
            }
            BinpatchesPackPatcher.apply(
                clean,
                listOf(BinpatchesPackPatcher.SidePatchInput("joined", null, patches)),
                output,
                log = { slog.info(it) },
                onProgress = { d, t -> total.set(t.toLong()); gate.tickTo(d.toLong()) }
            )
        }
        addExtraInnerClassMappings(clean, output, c)
        output
    }
}
