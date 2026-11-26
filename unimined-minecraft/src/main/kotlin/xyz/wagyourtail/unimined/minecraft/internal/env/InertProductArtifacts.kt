package xyz.wagyourtail.unimined.minecraft.internal.env

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.AssetIndex
import xyz.wagyourtail.unimined.minecraft.spi.EnvironmentCapabilities
import xyz.wagyourtail.unimined.minecraft.spi.LaunchMetadata
import xyz.wagyourtail.unimined.minecraft.spi.NamespaceMode
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifacts

/**
 * The [ProductArtifacts] for an environment that never declared a Minecraft
 * version — a source set that exists only as shared code pulled into real
 * environments via `combineWith` (e.g. Omnilook's `main`, compiled into both
 * its bta and reindev envs). In 1.x such a source set was simply never an
 * environment; in 2.x `uniminedMinecraft` auto-creates `main` for every
 * project, so a versionless env must resolve *inertly* — no spec, no
 * provider probe, no downloads, nothing on the classpath.
 *
 * Every surface is deliberately empty or identity so the usual lazy
 * consumers (classpath `withDependencies` rows, `useRawView`, `obfuscated`,
 * bytecode-fill jar scans, dev-namespace derivation) degrade to no-ops
 * instead of reaching for a product that does not exist.
 */
internal class InertProductArtifacts(project: Project) : ProductArtifacts {

    override val artifacts: Map<ArtifactKey, Provider<RegularFile>> =
        emptyMap()

    override val obfuscatedAuxArtifacts: Provider<List<Provider<RegularFile>>> =
        project.providers.provider { emptyList() }

    /** No shipped jar → nothing to deobfuscate; `!obfuscated` guards all
     *  skip their hops. */
    override val obfuscated: Provider<Boolean> =
        project.providers.provider { false }

    /** A canonical tree holding only `official` — enough for dev-namespace
     *  derivation to return `"official"` instead of failing "no mapping
     *  tree" on an env that composes no declared sources. */
    override val mappings: Provider<MappingTree> = project.providers.provider {
        MutableMappingTree.canonical(listOf(Namespace("official")))
    }

    /** Identity pipeline — `useRawView` short-circuits on this before ever
     *  consulting dev/source namespaces. */
    override val namespaceMode: Provider<NamespaceMode> =
        project.providers.provider { NamespaceMode.IDENTITY }

    /** No product floor — `javaVersion` reports the build JVM's major so
     *  the shared source set compiles at the project default level (the
     *  plain-`java`-plugin behavior a non-env source set would have). */
    override val capabilities: Provider<EnvironmentCapabilities> =
        project.providers.provider {
            EnvironmentCapabilities(
                minecraftVersion = "inert",
                javaVersion = JavaVersion.current().majorVersion.toInt()
            )
        }

    override val libraries: Provider<List<Dependency>> =
        project.providers.provider { emptyList() }

    override val extraDependencies: FileCollection = project.files()

    override val extraCompileSources: FileCollection = project.files()

    override val assets: Provider<AssetIndex> =
        project.providers.provider { null }

    override val launch: LaunchMetadata? = null
}
