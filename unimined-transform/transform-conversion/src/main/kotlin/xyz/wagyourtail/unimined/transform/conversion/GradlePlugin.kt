package xyz.wagyourtail.unimined.transform.conversion

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.conversion.internal.ConversionEnvironmentWiring
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry

/**
 * Plugin id `xyz.wagyourtail.unimined.transform.conversion`.
 *
 * Total-conversion transform: for environments declaring a
 * conversion-family loader (`reindev`/`bta`/`nsss`), the dist jar
 * replaces the Minecraft artifact entirely — no remap (dist classes
 * are already in the vanilla `official` shape). Modloaders layered
 * on top (foxloader, BTA fabric fork) are follow-up loader products
 * on the same family machinery.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            val minecraft = target.extensions.getByType(MinecraftExtension::class.java)
            // conversion mods run in the dist jar's own (official) namespace —
            // modSourceNamespaces keys on loaderSpec.name (the descriptor id,
            // not the family), so register each conversion product
            LoaderRegistry().family("conversion").forEach { descriptor ->
                minecraft.loaderModSourceNamespaces[descriptor.id] = listOf("official")
            }
            LoaderRegistry().family("conversion").flatMap { it.repos }.toSet()
                .forEach { repo -> repo.registerOn(target.repositories) }
            target.pluginManager.withPlugin("xyz.wagyourtail.unimined.transform") {
                minecraft.onEnvironmentBound { environment, _ ->
                    ConversionEnvironmentWiring.wire(target, environment)
                }
            }
        }
    }
}
