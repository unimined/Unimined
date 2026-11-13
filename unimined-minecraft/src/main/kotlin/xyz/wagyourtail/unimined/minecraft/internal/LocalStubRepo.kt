package xyz.wagyourtail.unimined.minecraft.internal

import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.util.writeAtomically
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.localrepo.LocalMavenPublisher
import java.io.File
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Local `unimined.generated` maven stub repository — publishes the
 * per-environment marker modules that let the MC artifact ride Gradle's
 * normal dependency resolution into the `artifactType` transform
 * pipeline (the stub's jar is a marker; the real jar materializes via
 * `ProductArtifactTransform`).
 *
 * The repo also serves `-sources.jar` classifier files under real
 * module coordinates for `genModSources` — no group filter, relying on
 * Gradle's artifact-level repository fallthrough.
 */
internal class LocalStubRepo(private val project: Project) {

    private var localRepoRegistered = false

    /** Local maven repo carrying `unimined.generated` stub modules. */
    val localRepoDir: Path
        get() = project.layout.projectDirectory
            .dir(".gradle/unimined/local-repo").asFile.toPath()

    /** (Re)write the env's stub module and return its `g:a:v` notation. */
    fun stubDependencyNotation(environment: MinecraftEnvironment): String {
        val artifactId = "minecraft-${environment.environmentId}"
        val version = environment.version.get()
        LocalMavenPublisher.publish(
            localRepoDir, STUB_GROUP, artifactId, version,
            mapOf("" to markerJar(localRepoDir))
        )
        return "$STUB_GROUP:$artifactId:$version"
    }

    fun ensure() {
        if (localRepoRegistered) return
        localRepoRegistered = true
        // No group filter: the repo also serves `-sources.jar` classifier
        // files under real module coordinates for `genModSources` —
        // Gradle's artifact-level repository fallthrough fetches them for
        // components whose metadata resolved elsewhere. Mod-coordinate
        // entries are file-only (no POM), so they can never shadow a real
        // module's descriptor.
        project.repositories.maven { repo ->
            repo.name = "uniminedGenerated"
            repo.url = localRepoDir.toUri()
        }
        // the env libraries bucket carries org.jetbrains:annotations
        // (dev jar's rewritten annotation surface) — real projects
        // resolve it from their own repos, but repo-less consumers
        // (ProjectBuilder fixtures, offline setups where this stub repo
        // is all there is) still need the module to exist. Publishing
        // the real jar off our own runtime classpath keeps the artifact
        // truthful even if this repo ends up first in repo order.
        annotationsJar()?.let { jar ->
            LocalMavenPublisher.publish(
                localRepoDir, "org.jetbrains", "annotations",
                JETBRAINS_ANNOTATIONS_VERSION, mapOf("" to jar)
            )
        }
    }

    /** The real `annotations-*.jar` already on the plugin's runtime
     *  classpath (kotlin-stdlib's compile dep), or null when the
     *  classloader serves it from an unpacked location. */
    private fun annotationsJar(): Path? {
        val url = javaClass.classLoader
            .getResource("org/jetbrains/annotations/NotNull.class") ?: return null
        return (url.openConnection() as? JarURLConnection)
            ?.jarFile?.let { File(it.name).toPath() }
    }

    private fun markerJar(repoDir: Path): Path {
        val marker = repoDir.resolve(".marker/marker.jar")
        if (Files.notExists(marker)) {
            Files.createDirectories(marker.parent)
            marker.writeAtomically { tmp ->
                JarOutputStream(Files.newOutputStream(tmp), Manifest()).close()
            }
        }
        return marker
    }

    internal companion object {
        const val STUB_GROUP = "unimined.generated"
        const val JETBRAINS_ANNOTATIONS_VERSION = "24.0.1"
    }
}
