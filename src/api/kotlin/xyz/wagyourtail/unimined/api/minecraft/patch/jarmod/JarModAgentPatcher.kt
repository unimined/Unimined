package xyz.wagyourtail.unimined.api.minecraft.patch.jarmod

import org.gradle.api.artifacts.Configuration
import org.jetbrains.annotations.ApiStatus

/**
 * @since 0.5.0
 */
interface JarModAgentPatcher : JarModPatcher {

    /**
     * @since 0.5.0
     * If should do the compile time transforms so normal jar
     * modding will be compatible with the output instead of requiring JarModAgent
     *
     * WARNING: may violate mojang's EULA... use at your own risk. this is not recommended and
     * is only here for legacy reasons and testing.
     */
    @Deprecated("may violate mojang's EULA... use at your own risk. this is not recommended and is only here for legacy reasons and testing.")
    var compiletimeTransforms: Boolean

    @get:ApiStatus.Internal
    val jarModAgent: Configuration

    /**
     * if the "agent" part should actually be enabled
     */
    var enableJarModAgent: Boolean

    /**
     * @since 1.0.0
     * add a transforms file to the jar mod agent
     */
    fun transforms(transform: String)

    /**
     * @since 1.0.0
     * add transforms files to the jar mod agent
     */
    fun transforms(transforms: List<String>)

    fun agentVersion(vers: String)