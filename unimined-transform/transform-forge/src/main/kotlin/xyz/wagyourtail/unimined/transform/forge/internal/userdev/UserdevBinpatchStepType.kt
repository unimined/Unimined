package xyz.wagyourtail.unimined.transform.forge.internal.userdev

import org.gradle.api.GradleException
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
 * archive (lzma → flat zip of `.binpatch` records, or a NeoForge
 * `NFPATCHBUNDLE001` bundle) to the dev-Minecraft jar **in process**
 * via [BinpatchesPackPatcher] — no tool JVM spawn (the external
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


    /** The jar carrying the lzma archive at [binpatchesKey] — the
     *  userdev jar for MERGED envs and combined-patch profiles
     *  (`config.binpatches`), the `:installer` jar for legacy
     *  split-side envs (`data/<side>.lzma`). Optional: absent on
     *  non-Userdev profiles (spec passes the jar through unchanged). */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val installerJar: RegularFileProperty

    /** Entry key inside [installerJar] holding the lzma archive. */
    @get:Input
    @get:Optional
    abstract val binpatchesKey: Property<String>

    /** The base jar the patches target — selects a NeoForge patch
     *  bundle's entries, as binarypatcher's `--base-type` does. The
     *  zip-record archives carry no base. */
    @get:Input
    @get:Optional
    abstract val baseType: Property<BinpatchesPackPatcher.PatchBase>

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
            val key = spec.binpatchesKey.get()
            val archive = spec.installerJar.get().asFile
            val lzma = Files.createDirectories(c.workDir.resolve("forge-binpatch/in"))
                .resolve("binpatches.lzma")
            java.util.jar.JarFile(archive).use { jar ->
                val entry = jar.getJarEntry(key)
                    ?: throw GradleException(
                        "no binpatches entry $key in ${archive.name} — " +
                                "split-side binpatching is not supported by " +
                                "this loader's packaging"
                    )
                jar.getInputStream(entry)
                    .use {
                        Files.copy(
                            it, lzma,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        )
                    }
            }
            val slog = c.log.subLogger("forge-binpatch")
            val total = AtomicLong()
            val gate = ProgressGate { elapsed, done ->
                slog.info {
                    "binpatching ${clean.fileName}: $done/${total.get()} entries in ${elapsed}ms"
                }
            }
            val onProgress = { d: Int, t: Int -> total.set(t.toLong()); gate.tickTo(d.toLong()) }
            if (BinpatchesPackPatcher.isPatchBundle(lzma)) {
                val base = spec.baseType.orNull
                    ?: throw GradleException(
                        "binpatches entry $key in ${archive.name} is a patch bundle, " +
                                "but the step has no base type to select its entries"
                    )
                val patches = BinpatchesPackPatcher.readPatchBundle(lzma, base)
                slog.info("read ${patches.size} $base patches from $key")
                BinpatchesPackPatcher.applyPatchBundle(
                    clean, patches, output,
                    log = { slog.info(it) },
                    onProgress = onProgress
                )
            } else {
                val patches = BinpatchesPackPatcher.readZipPatches(lzma)
                slog.info("read ${patches.size} binpatches from $key")
                BinpatchesPackPatcher.apply(
                    clean,
                    listOf(BinpatchesPackPatcher.SidePatchInput("joined", null, patches)),
                    output,
                    log = { slog.info(it) },
                    onProgress = onProgress
                )
            }
        }
        addExtraInnerClassMappings(clean, output, c)
        output
    }
}
