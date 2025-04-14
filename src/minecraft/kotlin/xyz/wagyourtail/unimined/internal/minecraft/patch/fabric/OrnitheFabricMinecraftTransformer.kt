package xyz.wagyourtail.unimined.internal.minecraft.patch.fabric

import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.minecraft.task.AbstractRemapJarTask
import xyz.wagyourtail.unimined.api.minecraft.task.RemapJarTask
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.LazyMutable
import xyz.wagyourtail.unimined.util.SemVerUtils

open class OrnitheFabricMinecraftTransformer(
    project: Project,
    provider: MinecraftProvider
): LegacyFabricMinecraftTransformer(project, provider) {

    override val defaultProdNamespace: String = "calamus"

    override fun additionalRemapJarConfiguration(task: AbstractRemapJarTask) {
        task.manifest {
            it.attributes(mapOf(
                "Calamus-Generation" to provider.mappings.ornitheGenVersion.toString()
            ))
        }
    }

    override fun addIntermediaryMappings() {
        provider.mappings {
            calamus()
        }
    }

}