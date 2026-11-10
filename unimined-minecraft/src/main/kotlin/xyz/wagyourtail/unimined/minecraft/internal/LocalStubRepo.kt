package xyz.wagyourtail.unimined.minecraft.internal

import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.util.writeAtomically
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.localrepo.LocalMavenPublisher
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

    private companion object {
        const val STUB_GROUP = "unimined.generated"
    }
}
