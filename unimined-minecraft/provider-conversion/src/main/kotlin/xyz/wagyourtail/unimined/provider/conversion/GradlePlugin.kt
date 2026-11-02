package xyz.wagyourtail.unimined.provider.conversion

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin id `xyz.wagyourtail.unimined.provider.conversion`.
 *
 * Total-conversion artifact provider — the dist jar replaces the
 * Minecraft artifact (1.x `AbstractTotalConversionProductProvider`).
 * Selected via `provider("conversion")` or claimed when the env's
 * loader is a conversion product.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            // provider registration rides the SPI service file —
            // nothing to wire eagerly
        }
    }
}
