package xyz.wagyourtail.unimined.transform

import org.gradle.api.GradleException
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.util.writeAtomically
import java.io.File
import java.nio.file.Files

/**
 * Extraction side of the bundled-resource channel — copies a
 * [BundledResource] out of the contributing plugin's jar into
 * `build/unimined/bundled/<envId>/<name>` and returns it as a
 * `project.files(...)`-ready [File].
 */
object BundledResourceChannel {

    /**
     * Materializes [resource] as a file dep for [envId]. Extraction is
     * atomic (temp-sibling write + move) — a partial copy can never sit
     * at the consumed path. [anchor] is a class in the contributing
     * plugin used to resolve the classpath resource — pass a class
     * from your own plugin (`FG1DynLibWiring::class.java`) so the
     * lookup survives plugin classloader layouts.
     */
    fun materialize(
        project: Project,
        envId: String,
        resource: BundledResource,
        anchor: Class<*>,
    ): File {
        val target = project.layout.buildDirectory
            .file("unimined/bundled/$envId/${resource.name}")
            .get().asFile
        if (!target.exists()) {
            target.parentFile.mkdirs()
            val stream = anchor.getResourceAsStream(resource.resourcePath)
                ?: throw GradleException(
                    "bundled resource '${resource.resourcePath}' not on " +
                            "${anchor.name}'s classpath"
                )
            target.toPath().writeAtomically { tmp ->
                stream.use {
                    Files.copy(it, tmp,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return target
    }
}
