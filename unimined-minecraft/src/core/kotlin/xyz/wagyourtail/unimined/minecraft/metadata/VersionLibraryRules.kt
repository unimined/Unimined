package xyz.wagyourtail.unimined.minecraft.metadata

import xyz.wagyourtail.unimined.minecraft.manifest.VersionJson

/**
 * Platform/artifact-shape evaluation for version-JSON `libraries` entries.
 *
 * Mojang metadata uses one entry for both the plain jar and its native
 * classifiers. Entries whose plain jar is absent (the legacy natives-only
 * shape) must not become bare maven dependencies: their classifier artifacts
 * are resolved by the natives channel instead. Loader metadata of the FG2 era
 * embeds the same library document shape, so this logic lives outside the
 * native manifest assembler.
 */
object VersionLibraryRules {

    /** Whether [library]'s rules allow it on [platform]. */
    fun appliesTo(
        library: VersionJson.Library,
        platform: PlatformInfo = PlatformInfo.current()
    ): Boolean {
        if (library.rules.isEmpty()) return true
        var allowed = false
        for (rule in library.rules) {
            if (matches(rule, platform)) allowed = rule.action == "allow"
        }
        return allowed
    }

    /**
     * Whether [library] denotes a resolvable plain jar. A library with a
     * natives mapping but no `downloads.artifact`/custom `url` is a natives
     * aggregate entry (legacy lwjgl/twitch shape), not a plain jar coordinate.
     */
    fun hasClasspathArtifact(library: VersionJson.Library): Boolean {
        if (library.url != null) return true
        if (library.downloads?.containsKey("artifact") == true) return true
        return library.natives == null
    }

    /**
     * The plain classpath coordinate for [library], or `null` when platform
     * rules exclude it or the entry only describes native classifier jars.
     */
    fun classpathCoordinate(
        library: VersionJson.Library,
        platform: PlatformInfo = PlatformInfo.current()
    ): String? =
        if (appliesTo(library, platform) && hasClasspathArtifact(library)) {
            library.name
        } else {
            null
        }

    private fun matches(rule: VersionJson.Rule, platform: PlatformInfo): Boolean {
        val osRule = rule.os
        if (osRule != null) {
            if (osRule.name != null && osRule.name != platform.osName) return false
            if (osRule.arch != null && !Regex(osRule.arch).containsMatchIn(platform.arch)) return false
            if (osRule.version != null && !Regex(osRule.version).containsMatchIn(platform.osVersion)) return false
        }
        return rule.features.orEmpty().all { (key, expected) ->
            (platform.features[key] ?: false) == expected
        }
    }

}
