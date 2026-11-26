package xyz.wagyourtail.unimined.transform.internal

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import java.io.File

/**
 * The [sourceSet]'s resource roots that hold authored files — every root
 * outside the project's build directory. Build-dir roots are task
 * outputs (the env's own generated mod metadata among them): a
 * configuration-time scan would read the previous build's output back
 * as input.
 */
fun authoredResourceDirs(project: Project, sourceSet: SourceSet): List<File> {
    // canonical on both sides — the project dir may sit behind a
    // symlink (macOS /var -> /private/var) that a declared root keeps
    val buildDir = project.layout.buildDirectory.get().asFile.canonicalFile.toPath()
    return sourceSet.resources.srcDirs.filter {
        !it.canonicalFile.toPath().startsWith(buildDir)
    }
}
