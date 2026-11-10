package xyz.wagyourtail.unimined.transform.conversion.internal

import xyz.wagyourtail.unimined.api.util.writeAtomically
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.io.File
import java.io.Serializable
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * FoxLoader hooks the game by *patching* the Minecraft jar at
 * build time — `com.fox2code.foxloader.invoker.Main` rewrites the
 * jar's entry points to call into `FoxLauncher`, which then loads
 * mods from `mods/`. Without this pass the env launches the vanilla
 * main class and the loader never initializes (mods staged under
 * `mods/` are silently ignored). Port of the 1.x
 * `FoxLoaderMinecraftTransformer.transform` javaexec.
 *
 * FoxLoader 2.x (reindev 2.9+, `loader` artifact) removed the 1.x
 * `PreLoader.patchReIndevForDev` entry point — the invoker's live
 * path is `Main(in, out, unpick)` → `PatchBridge.patch` →
 * `GamePatches.patchSlimJar`. `patchSlimJar` is a *unified* patch:
 * it requires the full client+server class set present in the input
 * and aborts on any missing target. ReIndev's dists can't be merged
 * (their shared `net/minecraft/common` classes diverge — the product
 * is `mergeable = false`), so for a sided env this processor builds
 * a *superset* patch input — the env's own jar plus the other
 * dist's unique entries — then strips the other side back out of
 * the patched output, leaving the env's real artifact shape.
 * The other dist is exposed via `artifacts[<other key>]` when the
 * product sets `fetchBothSides`.
 *
 * The invoker reflectively loads `PatchBridge` from the loader jar,
 * so the env wiring surfaces the whole `conversionLoader*` resolution
 * through `SpecContext.envArtifacts("foxloader:invoker")` and it all
 * rides `-cp`. `buildSpec` returns `null` when either binding is
 * absent so non-foxloader conversion envs (bta/nsss) get no step.
 */
class FoxLoaderPatchProcessor : JarProcessor {

    override val name = "foxloader-patch"
    // identity namespace — the invoker patches vanilla entry points
    // on the final (prod == dev) artifact shape
    override val preRemap = false

    class Spec(
        val invokerClasspath: List<String>,
        val otherSideJar: String,
    ) : JarProcessor.JarProcessorSpec, Serializable

    override fun buildSpec(context: JarProcessor.SpecContext): JarProcessor.JarProcessorSpec? {
        val invoker = context.envArtifacts("foxloader:invoker")
        val other = context.envArtifacts("foxloader:otherSide").firstOrNull()
        if (invoker.isEmpty() || other == null) return null
        return Spec(
            invoker.map { it.toAbsolutePath().toString() },
            other.toAbsolutePath().toString(),
        )
    }

    override fun serializeSpec(spec: JarProcessor.JarProcessorSpec): String =
        (spec as Spec).let {
            it.invokerClasspath.joinToString(File.pathSeparator) +
                    "@@" + it.otherSideJar
        }

    override fun deserializeSpec(payload: String): JarProcessor.JarProcessorSpec =
        payload.split("@@").let {
            Spec(it[0].split(File.pathSeparator), it[1])
        }

    override fun processJar(
        jar: Path,
        spec: JarProcessor.JarProcessorSpec,
        context: JarProcessor.ProcessorContext
    ) {
        spec as Spec
        val other = Path.of(spec.otherSideJar)
        // entry-name sets decide superset membership (input) and the
        // strip-back (output): keep = own entries ∪ patcher inserts
        // (names in neither jar)
        val ownNames = entryNames(jar)
        val otherOnlyNames = entryNames(other) - ownNames

        val superset = jar.resolveSibling("${jar.fileName}+foxloader-superset.jar")
        val patched = jar.resolveSibling("${jar.fileName}+foxloader-patched.jar")
        try {
            // superset = own jar + other-side-only entries; own
            // copies win shared names (it is the env's real artifact)
            Files.copy(jar, superset, StandardCopyOption.REPLACE_EXISTING)
            FileSystems.newFileSystem(superset, mapOf("create" to false))
                .use { outFs ->
                    FileSystems.newFileSystem(other).use { inFs ->
                        for (name in otherOnlyNames) {
                            val src = inFs.getPath(name)
                            if (Files.isDirectory(src)) continue
                            val dst = outFs.getPath(name)
                            dst.parent?.let { Files.createDirectories(it) }
                            Files.copy(src, dst)
                        }
                    }
                }

            val java = Path.of(
                System.getProperty("java.home"), "bin", "java"
            ).toString()
            val proc = ProcessBuilder(
                java, "-cp", spec.invokerClasspath.joinToString(File.pathSeparator),
                "com.fox2code.foxloader.invoker.Main",
                superset.toAbsolutePath().toString(),
                patched.toAbsolutePath().toString(),
                "false",
            ).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            context.log.info(output.trim())
            if (proc.waitFor() != 0 || !Files.exists(patched)) {
                throw IllegalStateException(
                    "foxloader invoker failed patching $jar:\n$output"
                )
            }

            // strip-back: copy patched -> jar minus other-side-only
            // entries. In-place is unsafe mid-iteration — write a tmp
            // then move over.
            jar.writeAtomically { tmp ->
                FileSystems.newFileSystem(patched).use { inFs ->
                    FileSystems.newFileSystem(
                        tmp, mapOf("create" to true)
                    ).use { outFs ->
                        inFs.rootDirectories.single().forEach { root ->
                            Files.walk(root).use { stream ->
                                stream.filter { !Files.isDirectory(it) }
                                    .forEach { src ->
                                        val name = src.toString()
                                            .removePrefix("/")
                                        if (name in otherOnlyNames)
                                            return@forEach
                                        val dst = outFs.getPath(name)
                                        dst.parent?.let {
                                            Files.createDirectories(it)
                                        }
                                        Files.copy(src, dst)
                                    }
                            }
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(superset)
            Files.deleteIfExists(patched)
        }
    }

    private fun entryNames(jar: Path): Set<String> =
        FileSystems.newFileSystem(jar).use { fs ->
            fs.rootDirectories.single().let { root ->
                Files.walk(root).use { stream ->
                    stream.filter { !Files.isDirectory(it) }
                        .map { it.toString().removePrefix("/") }
                        .toList().toSet()
                }
            }
        }
}
