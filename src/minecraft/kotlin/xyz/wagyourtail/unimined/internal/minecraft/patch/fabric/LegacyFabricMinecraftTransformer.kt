package xyz.wagyourtail.unimined.internal.minecraft.patch.fabric

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import xyz.wagyourtail.unimined.api.minecraft.patch.fabric.LegacyFabricPatcher
import xyz.wagyourtail.unimined.api.minecraft.task.AbstractRemapJarTask
import xyz.wagyourtail.unimined.api.minecraft.task.RemapJarTask
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.LazyMutable
import xyz.wagyourtail.unimined.internal.minecraft.resolver.Library
import xyz.wagyourtail.unimined.util.SemVerUtils

open class LegacyFabricMinecraftTransformer(
    project: Project,
    provider: MinecraftProvider
): FabricMinecraftTransformer(project, provider), LegacyFabricPatcher {

    override var replaceLwjglVersion: String? by FinalizeOnRead("2.9.4+legacyfabric.8")

    override val defaultProdNamespace: String = "legacyIntermediary"

    override fun addIntermediaryMappings() {
        provider.mappings {
            legacyIntermediary()
        }
    }

    override fun loader(dep: Any, action: Dependency.() -> Unit) {
        fabric.dependencies.add(
            (if (dep is String && !dep.contains(":")) {
                project.dependencies.create("net.fabricmc:fabric-loader:$dep")
            } else project.dependencies.create(dep)).apply(action)
        )
    }

    override fun addMavens() {
        super.addMavens()
        project.unimined.legacyFabricMaven()
    }

    override fun configureRemapJar(task: AbstractRemapJarTask) {
        if (fabricDep.version?.let { SemVerUtils.matches(it, ">=0.15.0") } == true) {
            project.logger.info("enabling mixin extra")
            if (task is RemapJarTask) {
                task.mixinRemap {
                    enableMixinExtra()
                }
            }
        }
    }

    override fun libraryFilter(library: Library): Library? {
        if (library.name.startsWith("org.lwjgl")) {
            val matches = library.name.split(":")
            if (matches.size == 3) {
                val (g, n, v) = matches
                if (v.startsWith("2")) {
                    return library.copy(name = "$g:$n:${replaceLwjglVersion ?: v}")
                }
            } else if (matches.size == 4) {
                val (g, n, v, c) = matches
                if (v.startsWith("2")) {
                    return library.copy(name = "$g:$n:${replaceLwjglVersion ?: v}:$c")
                }
            }
        }
        return super.libraryFilter(library)
    }
}