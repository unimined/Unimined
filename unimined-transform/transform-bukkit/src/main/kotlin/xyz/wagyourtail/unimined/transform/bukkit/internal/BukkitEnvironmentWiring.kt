package xyz.wagyourtail.unimined.transform.bukkit.internal

import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry

/**
 * Environment wiring for bukkit-family products — every product
 * resolves through the descriptor registry and contributes its
 * production-namespace convention; the *server artifact* strategy
 * (`serverArtifact` capability) decides how the patched jar
 * materializes:
 *
 *  - `distPatch` (paper) — dist-endpoint download + embedded-patch /
 *    bundler-extraction step on top of the provider's vanilla server
 *    jar (slices 2–3).
 *  - `provider` (craftbukkit/spigot) — a non-native `provider-*`
 *    module supplies the spigot-namespace server jar directly.
 *
 * Both land on the same raw→dev artifact view, so the user's chosen
 * dev namespace remaps through the standard pipeline.
 */
internal object BukkitEnvironmentWiring {

    fun wire(project: Project, environment: MinecraftEnvironment) {
        val descriptor = environment.loaderSpec
            .map { spec ->
                LoaderRegistry.resolveBuiltin(spec.name)
                    ?.takeIf { it.family == "bukkit" }
            }
        // server-side environments: provider stays the user's choice,
        // but the production mod-source convention comes from the
        // descriptor (spigot namespace for all bukkit products)
        environment.modSourceNamespaces.convention(
            descriptor.map { d ->
                if (d != null) listOf(d.prodNamespace) else emptyList()
            }
        )
    }

}
