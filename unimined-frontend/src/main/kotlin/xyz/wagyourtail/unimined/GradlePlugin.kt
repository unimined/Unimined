package xyz.wagyourtail.unimined

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin id `xyz.wagyourtail.unimined` — the frontend aggregation facade.
 * Applies the default stack (minecraft/remap/transform hosts, native
 * provider, bundled remap engines) and exposes `unimined { }`.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        for (id in DEFAULT_STACK) {
            target.pluginManager.apply(id)
        }
        warnOnLowDaemonHeap(target)
        target.extensions.create("unimined", UniminedExtension::class.java, target)
        wirePublishJars(target)
    }

    /** `remap<Env>Jar` — the prod-namespace publish artifact per env.
     *  Skipped (onlyIf) when identity-remap (unobf'd 26.x / dev==prod)
     *  or env.defaultRemapJar is off — the env's plain <env>Jar is the
     *  ship artifact then, with non-remap pipeline steps riding it.
     *  A `-dev` classifier variant of the dev jar is an optional
     *  publish extra (1.x remapJar/jar parity). */
    private fun wirePublishJars(target: Project) {
        val minecraft = target.extensions.getByType(
            xyz.wagyourtail.unimined.minecraft.MinecraftExtension::class.java)
        val remap = target.extensions.getByType(
            xyz.wagyourtail.unimined.remap.RemapExtension::class.java)
        minecraft.onEnvironmentBound { env, sourceSet ->
            val envId = env.environmentId
            val name = if (envId == "main") "remapJar"
                else "remap${envId.replaceFirstChar(Char::uppercase)}Jar"
            if (target.tasks.findByName(name) != null) return@onEnvironmentBound
            val enabled = target.providers.provider {
                env.defaultRemapJar.get() && !minecraft.isIdentityRemap(envId).get()
            }
            target.tasks.register(name,
                xyz.wagyourtail.unimined.remap.task.RemapJarTask::class.java) { jar ->
                jar.onlyIf { enabled.get() }
                jar.group = "build"
                jar.description =
                    "Remaps the '$envId' env jar to the prod namespace"
                jar.from(sourceSet.output)
                jar.surface.set(
                    xyz.wagyourtail.unimined.remap.spi.RemapSurface.JAR)
                jar.stepSpecs.set(remap.pipelineSteps(envId,
                    xyz.wagyourtail.unimined.remap.spi.RemapSurface.JAR))
                jar.sourceNamespace.set(minecraft.devNamespace(envId))
                jar.targetNamespace.set(target.providers.provider {
                    env.loaderSpec.orNull
                        ?.let { xyz.wagyourtail.unimined.transform.loader
                            .LoaderRegistry().resolve(it.name) }
                        ?.prodNamespace ?: "official"
                })
                jar.mappingFile.set(target.layout.buildDirectory.file(
                    "unimined/$envId/mappings.zip"))
            }
        }
    }

    /**
     * Remap/pipeline work currently executes inside the Gradle daemon
     * (artifact transforms), and the full Minecraft jar needs more headroom
     * than the default heap. Warn loudly below [MIN_DAEMON_HEAP] — this
     * check goes away once heavy steps move into worker isolation.
     */
    private fun warnOnLowDaemonHeap(target: Project) {
        if (Runtime.getRuntime().maxMemory() < MIN_DAEMON_HEAP) {
            target.logger.warn("")
            target.logger.warn("[Unimined] The Gradle daemon has less than ${MIN_DAEMON_HEAP shr 30}GB of heap.")
            target.logger.warn("[Unimined] Remapping Minecraft and mod artifacts will likely fail with OutOfMemoryError.")
            target.logger.warn("[Unimined] Add to your gradle.properties:")
            target.logger.warn("[Unimined]   org.gradle.jvmargs=-Xmx${MIN_DAEMON_HEAP shr 30}g")
            target.logger.warn("")
        }
    }

    private companion object {
        const val MIN_DAEMON_HEAP = 4L shl 30
        val DEFAULT_STACK = listOf(
            "xyz.wagyourtail.unimined.remap",
            "xyz.wagyourtail.unimined.mapping",
            "xyz.wagyourtail.unimined.mapping.uml",
            "xyz.wagyourtail.unimined.minecraft",
            "xyz.wagyourtail.unimined.transform",
            "xyz.wagyourtail.unimined.provider.native",
            "xyz.wagyourtail.unimined.remapper.tiny",
            "xyz.wagyourtail.unimined.remapper.uml",
            "xyz.wagyourtail.unimined.remapper.access",
            "xyz.wagyourtail.unimined.transform.fabric",
            "xyz.wagyourtail.unimined.transform.jarmod",
            "xyz.wagyourtail.unimined.mod",
            "xyz.wagyourtail.unimined.migrate",
            "xyz.wagyourtail.unimined.runs",
            "xyz.wagyourtail.unimined.ide",
            "xyz.wagyourtail.unimined.decompiler.vineflower"
        )
    }

}
