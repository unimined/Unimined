package xyz.wagyourtail.unimined.transform.forge.internal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.spec.LoaderSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.AccessRemapStepSpec
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepScope
import xyz.wagyourtail.unimined.remapper.access.ApplyClassTweakerStepSpec
import xyz.wagyourtail.unimined.remapper.access.ClassTweaker
import xyz.wagyourtail.unimined.remapper.jst.JstStepSpec
import xyz.wagyourtail.unimined.transform.JarProcessorsStepSpec

import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.McpConfigRunner
import xyz.wagyourtail.unimined.transform.forge.processor.UserdevInjectProcessor
import xyz.wagyourtail.unimined.transform.forge.processor.UserdevUniversalShadeProcessor
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import xyz.wagyourtail.unimined.transform.forge.internal.installer.ResolvedForge

/**
 * Mapping-hop wiring + srg/mcp export tasks. `contributeMappingHop`
 * injects the mcpconfig hop when the chain needs it; the `export*`
 * functions write the srg→mcp srg file and the unprotect mappings the
 * runtime bridge consumes.
 */
internal fun contributeMappingHop(
    project: Project,
    environment: MinecraftEnvironment,
    resolved: Provider<ResolvedForge>
) {
    val obfNs: Provider<String> = resolved.map { r ->
        (r.profile as? ForgeProfile.Userdev)?.config
            ?.obfNamespaceFor(!environment.obfuscated.get())
            ?: "official"
    }
    val mcpVersion: Provider<String> = resolved.map { r ->
        val mcp = (r.profile as? ForgeProfile.Userdev)?.config?.mcp
        mcp?.split(":")?.getOrNull(2)?.substringBefore("@") ?: ""
    }
    environment.mappingsSpec.deferredDefaultSources += {
        val spec = environment.loaderSpec.orNull
        // unobf'd product artifacts (26.x) ship no client_mappings —
        // dev is already official; the obf-namespace hop must not
        // contribute mojmap/searge channels that can't resolve; and
        // on non-forge envs `resolved` is empty so obfNs/version
        // providers would fail at query time
        if (spec == null ||
            LoaderRegistry.resolveBuiltin(spec.name)?.family != "forge" ||
            !environment.obfuscated.get()) emptyList()
        else listOf(
            MappingsSpecImpl.Source.Builtin(obfNs, mapOf("version" to mcpVersion))
        )
    }
    // the dev pipeline's core remap sees content at the profile's
    // "obf" boundary — srg for modern userdev (the binpatch step
    // substitutes the srg-named clean jar), obf for notchObf-era
    // (content stays obf until the rename)
    environment.devRemapSourceNamespace.set(obfNs)
}

/**
 * `Userdev` profile: the `forge-binpatch` JAR step — runs the
 * `binpatcher` tool over `binpatches.pack.lzma`. Spec fields stay
 * absent for non-Userdev profiles and the step passes the jar
 * through unchanged (lazy — `resolved` is only realized for
 * userdev-era products).
 */
internal fun exportSrgToMcp(
    tree: xyz.wagyourtail.unimined.mapping.MappingTree,
    prodNs: String,
    devNs: String,
    cacheDir: Path
): String = xyz.wagyourtail.unimined.transform.internal
    .RuntimeMappingExports.export(
        tree,
        xyz.wagyourtail.unimined.transform.internal.RuntimeMappingExports
            .Spec(
                format = xyz.wagyourtail.unimined.transform.internal
                    .RuntimeMappingExports.Format.SRG,
                srcNs = prodNs, dstNs = devNs,
                fileName = "srg2mcp.srg"
            ),
        cacheDir
    ).toString()

/**
 * 1.x `srgToMCPAsMCP` — MCP-zip `prodNs -> devNs` export:
 * `methods.csv`/`fields.csv`/`params.csv` in a jar. Comments off —
 * 1.x: "the reader forge uses now is too dumb".
 */
internal fun exportSrgToMcpZip(
    tree: xyz.wagyourtail.unimined.mapping.MappingTree,
    prodNs: String,
    devNs: String,
    cacheDir: Path
): String = xyz.wagyourtail.unimined.transform.internal
    .RuntimeMappingExports.export(
        tree,
        xyz.wagyourtail.unimined.transform.internal.RuntimeMappingExports
            .Spec(
                format = xyz.wagyourtail.unimined.transform.internal
                    .RuntimeMappingExports.Format.MCPV6_CSV_ZIP,
                srcNs = prodNs, dstNs = devNs,
                fileName = "srg2mcp.jar"
            ),
        cacheDir
    ).toString()

/**
 * Unprotect's mapping lookup — `mappings/mappings.tiny` inside a
 * zip, dst namespace literally `named` (hardcoded in unprotect's
 * TargetCache). `TargetCache.isMinecraftClass` matches *loaded*
 * class names against that column — the dev runtime runs at the
 * dev namespace, so `named` must carry the tree's dev (last)
 * namespace. Exported with `nsRenamer` so it always presents as
 * `named`.
 */
internal fun exportUnprotectMappings(
    tree: xyz.wagyourtail.unimined.mapping.MappingTree,
    cacheDir: Path
): String {
    val dst = tree.namespaces.last()
    return xyz.wagyourtail.unimined.transform.internal
        .RuntimeMappingExports.export(
            tree,
            xyz.wagyourtail.unimined.transform.internal.RuntimeMappingExports
                .Spec(
                    format = xyz.wagyourtail.unimined.transform.internal
                        .RuntimeMappingExports.Format.TINY_JAR,
                    nsRenamer = {
                        if (it == dst) xyz.wagyourtail.unimined.mapping
                            .Namespace("named") else it
                    },
                    renamerKey = "unprotect:dst=named",
                    fileName = "unprotect-mappings.jar"
                ),
            cacheDir
        ).toString()
}
