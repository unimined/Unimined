package xyz.wagyourtail.unimined.adapter.patchbase

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.jvm.tasks.Jar
import xyz.wagyourtail.patchbase.gradle.tasks.ApplySourcePatchTask
import xyz.wagyourtail.patchbase.gradle.tasks.CreateClassPatchTask
import xyz.wagyourtail.patchbase.gradle.tasks.CreateSourcePatchTask
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment

/**
 * Per-env `patchBase` DSL object — registered as the `patchBase`
 * extension on each environment by the adapter plugin.
 *
 * ```
 * sourceSet("main") {
 *     patchBase("my.patches:base-patch:1.0@zip") // consume a patchset
 *     patchBaseCreator()                        // author a patchset
 * }
 * ```
 */
class PatchBaseEnvExtension(
    private val project: Project,
    private val env: MinecraftEnvironment,
    private val configuration: Configuration
) {

    private val sfx = env.environmentId.replaceFirstChar { it.uppercase() }
    private val taskSfx = if (env.environmentId == "main") "" else sfx
    private val patchDir = if (env.environmentId == "main")
        "patches" else "patches/${env.environmentId}"
    private val srcDir = if (env.environmentId == "main")
        "src/main/java" else "src/${env.environmentId}/java"

    /** Add a patch jar dependency — applied to this env's MC jar
     *  pre-remap (prod namespace). Consume side: declare a patchset
     *  dependency and its `.bdiff` set lands on the dev + compile
     *  classpaths via the env artifact. */
    fun patchBase(notation: Any) {
        project.dependencies.add(configuration.name, notation)
    }

    /**
     * Authoring side — wires the three patch-set tasks against this
     * env's handles:
     *
     * - `applySourcePatch<Sfx>` — decompiled env sources + `patches/`
     *   `.patch` set → writes ONLY the patch-covered files into
     *   `src/<env>/java` (gitignore it — it's generated material).
     *   Compile + JarModAgent's priority classpath then override the
     *   stock classes at dev runtime.
     * - `createSourcePatch<Sfx>` — diffs `src/<env>/java` back into
     *   `patches/` (the git-tracked repo content).
     * - `createClassPatch<Sfx>` — diffs the env's prod jar against its
     *   pre-remap base, producing the shippable `.bdiff` patch jar
     *   (checksums bind to the raw base — the same artifact consumers'
     *   `patchBase()` dep applies to).
     */
    fun patchBaseCreator() {
        val envId = env.environmentId
        val genSources = project.tasks.named(
            if (envId == "main") "genSources" else "gen${sfx}Sources")

        project.tasks.register("applySourcePatch$taskSfx",
            ApplySourcePatchTask::class.java) {
            it.group = "patchbase"
            it.patchDir.set(project.file(patchDir))
            it.outputDir.set(project.file(srcDir))
            it.sources.set(project.files(genSources))
        }
        project.tasks.register("createSourcePatch$taskSfx",
            CreateSourcePatchTask::class.java) {
            it.group = "patchbase"
            it.sourceDir.set(project.file(srcDir))
            it.outputDir.set(project.file(patchDir))
            it.sources.set(project.files(genSources))
        }

        // prod artifact: remap<Env>Jar when remap runs, else the env's
        // own jar; resolved lazily so either registration order wins
        val prodJar = project.provider {
            sequenceOf("remap${taskSfx.ifEmpty { "" }}Jar",
                "remap${sfx}Jar",
                if (envId == "main") "jar" else "${envId}Jar")
                .distinct()
                .mapNotNull { n -> project.tasks.findByName(n) }
                .firstOrNull()
                ?: error("no prod jar task for env '$envId'")
        }.flatMap { (it as Jar).archiveFile }

        project.tasks.register("createClassPatch$taskSfx",
            CreateClassPatchTask::class.java) {
            it.group = "patchbase"
            it.inputFile.set(prodJar)
            it.classpath.set(project.files(
                project.configurations.named(env.minecraftRawViewName)))
            it.archiveClassifier.set(
                if (envId == "main") "patch" else "patch-$envId")
        }
    }

}
