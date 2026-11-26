package xyz.wagyourtail.unimined.transform.forge.internal

import xyz.wagyourtail.unimined.mapping.internal.MinecraftVersions

/** How a userdev `universal` jar reaches the dev runtime. */
internal enum class UserdevUniversalLinkage {
    /** Legacy userdev shape: overlay the universal into the patched
     *  Minecraft jar and strip its manifest (pre-FML-10 discovery). */
    SHADED,

    /** FML 10/11 shape (NeoForge 1.21.9–1.21.11): overlay the
     *  universal into the class-only patched jar, retaining its
     *  `FML-System-Mods` manifest; Minecraft resources stay in
     *  `client-extra`, so the two classpath roots remain distinct. */
    SHADED_WITH_MANIFEST,

    /** FML 12+/unobfuscated shape (MC after 1.21.11): do not shade at
     *  all — the universal is a separate compile/runtime classpath
     *  root beside the plain patched game jar. */
    SEPARATE
}

/** Era gate mirrored from ModDevGradle's
 *  `VersionCapabilitiesInternal.needsNeoForgeInMinecraftJar`: through
 *  1.21.11 NeoForge is injected into the Minecraft jar (with its
 *  manifest from 1.21.9 onward); later versions keep the universal
 *  separate. Unparseable/custom version ids conservatively keep the
 *  legacy shaded behavior. */
internal fun userdevUniversalLinkage(mcVersion: String): UserdevUniversalLinkage =
    runCatching {
        when {
            MinecraftVersions.compare(mcVersion, "1.21.9") < 0 ->
                UserdevUniversalLinkage.SHADED
            MinecraftVersions.compare(mcVersion, "1.21.11") <= 0 ->
                UserdevUniversalLinkage.SHADED_WITH_MANIFEST
            else -> UserdevUniversalLinkage.SEPARATE
        }
    }.getOrDefault(UserdevUniversalLinkage.SHADED)
