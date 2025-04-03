package xyz.wagyourtail.unimined.internal.minecraft.patch.bukkit

import io.sigpipe.jbsdiff.Patch
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.minecraft.patch.bukkit.PaperPatcher
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.util.*
import java.io.File
import java.net.URI
import java.nio.file.StandardOpenOption
import kotlin.collections.first
import kotlin.collections.plus
import kotlin.collections.setOf
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes

open class PaperMinecraftTransformer(project: Project,
                                provider: MinecraftProvider
) : CraftbukkitMinecraftTransformer(project, provider, "paper"), PaperPatcher {

    override val supportedEnvs = setOf(EnvType.SERVER)

    override var build: Int by MustSet()

    override var paper: File by FinalizeOnRead(LazyMutable {
        project.cachingDownload(
            URI.create("https://api.papermc.io/v2/projects/paper/versions/${provider.version}/builds/$build/downloads/paper-${provider.version}-$build.jar"),
            cachePath = project.unimined.getGlobalCache().resolve("paper/${provider.version}/paper-${provider.version}-${build}.jar")
        ).toFile()
    })

    override var patchName by FinalizeOnRead(Regex("META-INF/.*/server-.*\\.patch"))

    init {
        project.unimined.paperMaven()
        loader = provider.version
    }

    override fun loadLibraries() {
        // TODO
    }

    override fun transform(minecraft: MinecraftJar): MinecraftJar {
        val output = MinecraftJar(
            minecraft,
            patches = minecraft.patches + "paper-$loader",
            mappingNamespace = provider.mappings.checkedNs("spigotProd")
        )

        val content = paper.toPath().readZipContents()
        // find patch
        val patch = content.first {
            it == "paperMC.patch" || it.matches(patchName)
        }

        output.path.outputStream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { os ->
            paper.toPath().readZipInputStreamFor(patch) {
                Patch.patch(minecraft.path.readBytes(), it.readBytes(), os)
            }
        }

        return output
    }

}
