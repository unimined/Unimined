package xyz.wagyourtail.unimined.minecraft.transform

import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.minecraft.MinecraftArtifactProducerRegistry
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeLoaderSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeMinecraftSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeVersionSpec
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactProductionRequest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

/**
 * Converts a marker-stub jar into the environment's raw Minecraft artifact
 * (client/server/merged jar in the provider's source namespace) at
 * resolution time — lazily, cacheably, and during IDE sync.
 *
 * Producer impls are rediscovered via `ServiceLoader` inside the action so
 * nothing Gradle-model lives in serialized transform state. The spec is
 * deliberately loader-free: this transform produces the *vanilla-side*
 * artifact; loader transformation is the dev-pipeline step's job.
 */
@CacheableTransform
abstract class MinecraftArtifactTransform : TransformAction<MinecraftArtifactTransform.Params> {

    interface Params : TransformParameters {

        @get:Input
        val environmentId: Property<String>

        @get:Input
        val version: Property<String>

        @get:Input
        val side: Property<String>

        @get:Input
        val artifactKey: Property<String>

        /** Producer selection override (`minecraft.provider`). */
        @get:Optional
        @get:Input
        val producerName: Property<String>

        /** Selected producer's output-format version — bumps the cache
         *  key whenever the production pipeline's output changes for
         *  identical inputs. */
        @get:Input
        val artifactFormatVersion: Property<Int>

        /** Provider cache root — treated as a content-verified side cache,
         *  not a fingerprinted input. */
        @get:Internal
        val cacheDir: Property<String>

        @get:Input
        val offline: Property<Boolean>

        @get:Input
        val refreshUniminedDownloads: Property<Boolean>

        @get:Input
        val rebuildUniminedArtifacts: Property<Boolean>

        @get:Optional
        @get:Input
        val metadataCacheTtl: Property<String>

    }

    @get:InputArtifact
    @get:Classpath
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val p = parameters
        val spec = RuntimeMinecraftSpec(
            environmentId = p.environmentId.get(),
            sourceSetName = p.environmentId.get(),
            version = RuntimeVersionSpec(p.version.get()),
            side = Side.valueOf(p.side.get()),
            loader = null
        )
        val producer = MinecraftArtifactProducerRegistry()
            .select(spec, p.producerName.orNull)
        val request = ArtifactProductionRequest(
            cacheDir = Path.of(p.cacheDir.get()),
            artifactKey = ArtifactKey.valueOf(p.artifactKey.get()),
            offline = p.offline.get(),
            refreshUniminedDownloads = p.refreshUniminedDownloads.get(),
            rebuildUniminedArtifacts = p.rebuildUniminedArtifacts.get(),
            metadataCacheTtl = p.metadataCacheTtl.orNull?.let(Duration::parse),
            logger = Logging.getLogger(MinecraftArtifactTransform::class.java)
        )
        val produced = producer.produce(spec, request)
        val output = outputs.file(
            "minecraft-${p.environmentId.get()}-${p.version.get()}-" +
                p.artifactKey.get().lowercase() + ".jar"
        ).toPath()
        Files.copy(produced, output, StandardCopyOption.REPLACE_EXISTING)
    }

}
