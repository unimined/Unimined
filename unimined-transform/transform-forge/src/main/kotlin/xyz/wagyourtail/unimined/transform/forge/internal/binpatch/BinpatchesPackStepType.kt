package xyz.wagyourtail.unimined.transform.forge.internal.binpatch

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.api.util.ProgressGate
import xyz.wagyourtail.unimined.remap.spi.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import java.util.jar.JarFile
import kotlin.reflect.KClass
import xyz.wagyourtail.unimined.transform.forge.internal.binpatch.BinpatchesPackPatcher

/**
 * `forge-binpatch-pack` JAR-surface step — FG2-era binary patching:
 * reads `binpatches.pack.lzma` out of the resolved universal jar (or
 * `devbinpatches.pack.lzma` out of a FG2 `userdev` dev package) and
 * applies it with [BinpatchesPackPatcher], the in-process GDiff
 * patcher (1.x `FG2MinecraftTransformer.transformIntern` +
 * `FG2TaskApplyBinPatches` — no external tool needed, unlike FG3's
 * `binpatcher` exec).
 *
 * Passthrough when fields are absent (non-Binpatches profiles).
 */
abstract class BinpatchesPackStepSpec : DevArtifactOnlyStepSpec {

    /** The jar carrying the patch pack — universal jar or dev package. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val loaderJar: RegularFileProperty

    /** Entry inside [loaderJar] — `binpatches.pack.lzma` on universal
     *  jars, `devbinpatches.pack.lzma` on dev packages. Absent on
     *  non-Binpatches profiles (step is a passthrough then). */
    @get:Optional
    @get:Input
    abstract val packEntry: Property<String>

    /** Patch set selector — `client` or `server`. Absent on
     *  non-Binpatches profiles. */
    @get:Optional
    @get:Input
    abstract val side: Property<String>

    /** The clean client jar — used to attribute merged-jar entries
     *  to their origin side (1.x patches each side's clean jar
     *  *before* merging; on the merged jar the equivalent is
     *  adler-matching each entry against the side jars). */
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val clientJar: RegularFileProperty

    /** The clean server jar — see [clientJar]. */
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val serverJar: RegularFileProperty
}

class BinpatchesPackStepType : RemapStepType<BinpatchesPackStepSpec> {

    override val name = "unimined"
    override val stepName = "forge-binpatch-pack"
    override val specType: KClass<BinpatchesPackStepSpec> = BinpatchesPackStepSpec::class
    override val surfaces = setOf(RemapSurface.JAR)
    override val needsMappingTree = true

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: BinpatchesPackStepSpec, ctx: StepContext) = ArtifactStep { input, c ->
        if (!spec.loaderJar.isPresent || !spec.packEntry.isPresent)
            return@ArtifactStep input
        val workDir = Files.createDirectories(c.workDir.resolve("forge-binpatch-pack"))
        val output = workDir.resolve("patched.jar")
        if (!Files.exists(output)) {
            val pack = workDir.resolve("binpatches.pack.lzma")
            JarFile(spec.loaderJar.get().asFile).use { jar ->
                val entry = jar.getJarEntry(spec.packEntry.get())
                    ?: error("${spec.packEntry.get()} not found in ${spec.loaderJar.get()}")
                jar.getInputStream(entry).use {
                    Files.copy(it, pack, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            val side = spec.side.get()
            // 1.x patches each side's clean jar *before* merging; the
            // merged environment only has the combined jar, so both
            // patch sets apply with per-entry side attribution against
            // the raw side jars
            val sides = if (side == "joined") listOf("client", "server")
            else listOf(side)
            // lzma decompress + pack200 unpack once — both side patch
            // sets read from the same unpacked pack jar
            val packBytes = BinpatchesPackPatcher.unpackPack(pack)
            val sideInputs = sides.map { s ->
                val jar = when (s) {
                    "client" -> spec.clientJar.orNull?.asFile?.toPath()
                    else -> spec.serverJar.orNull?.asFile?.toPath()
                }
                BinpatchesPackPatcher.SidePatchInput(
                    s, jar, BinpatchesPackPatcher.readPatches(packBytes, s)
                )
            }
            val slog = c.log.subLogger("forge-binpatch")
            slog.info(
                "read " +
                        sideInputs.joinToString("+") { "${it.patches.size} ${it.side}" } +
                        " binpatches for $side")
            val total = AtomicLong()
            val gate = ProgressGate { elapsed, done ->
                slog.info {
                    "binpatching ${input.fileName}: $done/${total.get()} entries in ${elapsed}ms"
                }
            }
            BinpatchesPackPatcher.apply(
                input, sideInputs, output,
                log = { slog.info(it) },
                onProgress = { d, t -> total.set(t.toLong()); gate.tickTo(d.toLong()) }
            )
        }
        addExtraInnerClassMappings(input, output, c)
        output
    }
}
