package xyz.wagyourtail.unimined.provider.legacydist

import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.spec.DeclaredSource
import xyz.wagyourtail.unimined.api.spec.MinecraftSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.minecraft.spi.MinecraftArtifacts
import xyz.wagyourtail.unimined.minecraft.spi.MinecraftProvider
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Declared-source provider — `provider("legacydist")`, or implicitly
 * when an env declares `legacydist {}`. Claims any spec carrying a
 * `declaredSource`; materializes it (local file / URL+SHA-256 /
 * external producer) into a content-addressed cache under
 * `.gradle/unimined/legacydist-cache`. No manifest is consulted —
 * works for non-MC-version jars (BTA/ReIndev, pre-1.8 bukkit,
 * air-gapped artifacts).
 *
 * Maven-coordinate sources are not yet resolvable — providers have
 * no configuration access; declare `file`/`url` instead.
 */
class LegacyDistProvider : MinecraftProvider {

    override val name: String = "legacydist"

    override fun supports(spec: MinecraftSpec): Boolean =
        spec.declaredSource != null

    override fun resolve(spec: MinecraftSpec, ctx: ProviderContext): MinecraftArtifacts {
        val declared = spec.declaredSource
            ?: throw GradleException("legacydist selected without a declaredSource")
        val cacheDir = ctx.layout.projectDirectory
            .dir(".gradle/unimined/legacydist-cache").asFile
        val jar = ctx.providers.provider {
            materialize(spec, declared.source, cacheDir, ctx)
        }
        return LegacyDistArtifacts(spec, declared, jar, ctx)
    }

    private fun materialize(
        spec: MinecraftSpec,
        source: DeclaredSource,
        cacheDir: File,
        ctx: ProviderContext
    ): File {
        val key = sha256Hex(
            when (source) {
                is DeclaredSource.LocalFile -> "file:${source.path}"
                is DeclaredSource.Maven -> "maven:${source.notation}"
                is DeclaredSource.Url -> "url:${source.sha256 ?: source.url}"
                is DeclaredSource.Producer -> "producer:${source.executable}:${source.args.joinToString(",")}"
            }
        )
        val dir = File(cacheDir, key).apply { mkdirs() }
        val jar = File(dir, "${spec.environmentId}-${spec.version.version}.jar")
        if (jar.exists() && !ctx.rebuildUniminedArtifacts) return jar

        return when (source) {
            is DeclaredSource.LocalFile -> {
                val src = ctx.layout.projectDirectory.file(source.path).asFile
                    .let { if (it.exists()) it else File(source.path) }
                if (!src.exists()) throw GradleException(
                    "legacydist declared file not found: ${source.path}"
                )
                src.copyTo(jar, overwrite = true)
                jar
            }

            is DeclaredSource.Url -> {
                if (ctx.offline) {
                    if (jar.exists()) return jar
                    throw GradleException(
                        "legacydist url source uncached and --offline: ${source.url}"
                    )
                }
                if (source.sha256 == null) ctx.logger.warn(
                    "[Unimined/legacydist] url source ${source.url} has no" +
                            " sha256 pin — cached by URL hash only"
                )
                download(source.url, jar)
                if (source.sha256 != null) {
                    val actual = sha256Hex(jar.readBytes())
                    if (!actual.equals(source.sha256, ignoreCase = true)) {
                        jar.delete()
                        throw GradleException(
                            "legacydist sha256 mismatch for ${source.url}:" +
                                    " expected ${source.sha256}, got $actual"
                        )
                    }
                }
                jar
            }

            is DeclaredSource.Producer -> {
                ctx.exec.exec { exec ->
                    exec.workingDir = dir
                    exec.executable = source.executable
                    exec.args(*source.jvmArgs.toTypedArray(), *source.args.toTypedArray())
                }.rethrowFailure()
                val out = File(dir, source.output)
                if (!out.exists()) throw GradleException(
                    "legacydist producer did not emit ${source.output}"
                )
                if (out != jar) out.renameTo(jar)
                jar
            }

            is DeclaredSource.Maven -> throw GradleException(
                "legacydist maven sources are not resolvable — providers" +
                        " cannot resolve configurations; declare the" +
                        " artifact via file() or url() instead"
            )
        }
    }

    private fun download(url: String, dest: File) {
        val conn = URI.create(url).toURL()
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.inputStream.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
    }

    private fun sha256Hex(data: String): String =
        sha256Hex(data.toByteArray())

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(data).joinToString("") { "%02x".format(it) }

}

internal fun Side.artifactKey() = when (this) {
    Side.CLIENT -> xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey.CLIENT
    Side.SERVER -> xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey.SERVER
    Side.MERGED -> xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey.MERGED
}
