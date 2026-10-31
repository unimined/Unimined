package xyz.wagyourtail.unimined.transform

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import xyz.wagyourtail.unimined.transform.task.TransformMinecraftTask
import xyz.wagyourtail.unimined.transform.spi.MinecraftTransformer
import xyz.wagyourtail.unimined.transform.spi.TransformContext
import xyz.wagyourtail.unimined.transform.spi.TransformerClaim
import xyz.wagyourtail.unimined.transform.feature.EnvironmentFeature
import java.nio.file.Files

/**
 * `custom` — the declared-capabilities fallback transform. Claims an
 * environment only when no bundled transform claims its loader
 * `family` ([CustomTransformerClaim.fallbackClaim]); its own task is a
 * passthrough — all real work is the env's declared `pipeline { }`
 * steps (which ride the jar-processors channel) and `features { }`
 * bindings resolved through the env's FeatureRegistry.
 */
class CustomTransformer(
    private val ctx: TransformerClaim.TransformerEnvContext
) : MinecraftTransformer {

    override val transformTaskProvider: TaskProvider<out TransformMinecraftTask> =
        ctx.project.tasks.register(
            "transformCustom_${ctx.env.environmentId}",
            PassthroughTransformTask::class.java
        )

    override fun configure(ctx: TransformContext) {
        val input: Provider<RegularFile> = ctx.inputs.lastOrNull()?.artifact
            ?: this.ctx.env.let { env ->
                ctx.minecraft.artifacts.entries.firstOrNull()?.value
                    ?: throw GradleException(
                        "custom transform: env '${ctx.environmentId}' resolved " +
                                "no artifacts and no prior transform outputs"
                    )
            }
        transformTaskProvider.configure {
            it.inputArtifact.set(input)
            it.outputArtifact.set(
                this.ctx.project.layout.buildDirectory
                    .file("unimined/custom/${ctx.environmentId}/dev.jar")
            )
        }
        ctx.emit("jar", transformTaskProvider.flatMap { it.outputArtifact })
        // declared feature bindings — validate applies() at selection,
        // run-config surface mutators flow into transformRun
        runFeatures = this.ctx.env.features.resolve(featureContext(ctx))
    }

    private var runFeatures: List<EnvironmentFeature> = emptyList()

    private fun featureContext(ctx: TransformContext) =
        object : EnvironmentFeature.FeatureContext {
            override val bindingValue: String? = null
            override val side: String? = null
            override val family: String? = this@CustomTransformer.ctx.descriptor?.family
        }

    override fun transformRun(config: xyz.wagyourtail.unimined.api.spec.RunConfigSpec) {
        // feature-declared run mutators apply in binding order —
        // EnvironmentFeature run-surface impls register their actions
        // on the env's runMutators during environment wiring
    }

}

/**
 * Passthrough transform task — the declared steps' work lives in the
 * jar-processors artifact step; this task only carries the input to
 * the emitted dev output so the pipeline has a `jar` edge.
 */
abstract class PassthroughTransformTask : TransformMinecraftTask() {

    @TaskAction
    fun run() {
        Files.copy(
            inputArtifact.get().asFile.toPath(),
            outputArtifact.get().asFile.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
    }

}
