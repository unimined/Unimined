package xyz.wagyourtail.unimined.transform.jarmod.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment

/**
 * Rift env wiring — 1.x `RiftMinecraftTransformer` parity. Rift is a
 * LaunchWrapper-family loader, not a jar merge: the rift jar is a
 * library dep, launchwrapper/mixin/ASM go on `minecraftLibraries`
 * (non-transitive, matching the 1.x classpath), and runs launch via
 * `net.minecraft.launchwrapper.Launch` with the side's
 * `RiftLoader*Tweaker`. Versions are MC-keyed — `loader("rift")`
 * resolves `org.dimdev:rift:<mcVersion>`.
 */
internal object RiftWiring {

    /**
     * ModernMixins — RiftEnthusiasts' rift mod bundling mixin 0.8.6 +
     * MixinExtras 0.3.6. Modrinth-only distribution; the version is a
     * modrinth version-id (`1.0.0+mc1.13.2-rift`, the only rift
     * release). At launch its listener strips the dimdev
     * mixin-0.7.11/asm-6.2 jars off the classpath and parent-loads its
     * shaded mixin 0.8.6 instead — so the stock trio must stay on the
     * classpath: its conflict detection keys off those jar names, and
     * its no-conflict fallback path is broken (unloads its own jar).
     */
    const val MODERN_MIXINS = "maven.modrinth:modernmixins:8c7jHosR"

    fun wire(project: Project, environment: MinecraftEnvironment) {
        val mcVersion = environment.version.get()
        val declaredVersion = environment.spec().loader?.version
            ?.takeIf { it.isNotEmpty() } ?: mcVersion

        project.dependencies.add(
            environment.modImplementationConfigurationName,
            "org.dimdev:rift:$declaredVersion"
        )

        // 1.x pinned these non-transitively — rift's pom already
        // carries dimdev's mixin; keeping the trio explicit matches
        // the original classpath without letting it pull a tree
        val libs = mutableListOf<String>()
        // ModernMixins ahead of the trio so compile-classpath order
        // exposes its mixin 0.8.6 + MixinExtras API (superset of 0.7)
        if (JarModEnvState.modernMixins(environment.environmentId)) {
            libs += MODERN_MIXINS
        }
        libs += listOf(
            "org.dimdev:mixin:0.7.11-SNAPSHOT",
            "net.minecraft:launchwrapper:1.12",
            "org.ow2.asm:asm:6.2",
            "org.ow2.asm:asm-commons:6.2",
            "org.ow2.asm:asm-tree:6.2"
        )
        libs.forEach { gav ->
            (project.dependencies.add(
                environment.minecraftLibrariesConfigurationName, gav
            ) as? ModuleDependency)?.isTransitive = false
        }

        val transform = project.extensions.getByType(
            xyz.wagyourtail.unimined.transform.TransformExtension::class.java
        )
        transform.sourceSet(environment.environmentId).runMutators +=
            org.gradle.api.Action { config ->
                // tweakers need LaunchWrapper's main, not the
                // version-json default (`net.minecraft.client.main.Main`)
                config.mainClass = "net.minecraft.launchwrapper.Launch"
                config.programArgs += listOf(
                    "--tweakClass",
                    if (config.name.contains("server", ignoreCase = true))
                        "org.dimdev.riftloader.launch.RiftLoaderServerTweaker"
                    else
                        "org.dimdev.riftloader.launch.RiftLoaderClientTweaker"
                )
                // rift has no mixin-config discovery of its own and the
                // `-Dmixin.configs` sysprop is ModLauncher-era only —
                // `--mixin` args are read off `sun.java.command` by the
                // LaunchWrapper mixin platform manager on both 0.7.x
                // and 0.8.x, so they register dev configs regardless of
                // which mixin runtime ends up active. Mutators run at
                // launch assembly — reading the env's config list here
                // is post-DSL. Duplicates (the 0.8 exploded-dir
                // manifest scan registers them too) dedupe by name.
                environment.mixinConfigs.get().forEach {
                    config.programArgs += listOf("--mixin", it)
                }
            }
    }
}
