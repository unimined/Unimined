package xyz.wagyourtail.unimined.api.util

import org.gradle.api.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories

/**
 * Artifact-cache roots for unimined — the 1.x/loom model: content-identical
 * artifacts (downloads, merges keyed by version+side+producer) live in the
 * *global* gradle-user-home cache so sibling projects share them; anything
 * stamped by project state (env stubs, generated sources carrying project
 * transforms/ATs) stays under the project's `.gradle/unimined`.
 *
 * `unimined.useGlobalCache=false` (1.x flag, default true) redirects the
 * global root under the root project's `.gradle` for hermetic builds.
 */
object UniminedCache {

    const val USE_GLOBAL_CACHE = "unimined.useGlobalCache"

    /** Root for content-keyed, cross-project-sharable artifacts. */
    fun global(project: Project): Path =
        if (project.providers.gradleProperty(USE_GLOBAL_CACHE)
                .map { it.toBoolean() }.getOrElse(true)
        ) {
            project.gradle.gradleUserHomeDir.toPath()
                .resolve("caches").resolve("unimined")
        } else {
            project.rootProject.layout.projectDirectory
                .dir(".gradle/unimined").asFile.toPath()
        }.createDirectories()

    /** Root for project-scoped artifacts — always under the project's
     *  `.gradle` regardless of the flag (matches 1.x local-repo/stub
     *  placement). */
    /** Content-ish key for a resolved artifact — name + size + mtime,
     *  stat-only (no hashing). A changed file re-extracts; it never
     *  silently collides. */
    fun artifactKey(path: Path): String {
        val a = Files.readAttributes(path, BasicFileAttributes::class.java)
        val n = path.fileName.toString().removeSuffix(".jar")
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        return "$n-${a.size()}-${a.lastModifiedTime().toMillis()}"
    }

    fun project(project: Project): Path =
        project.layout.projectDirectory
            .dir(".gradle/unimined").asFile.toPath()
            .createDirectories()

}
