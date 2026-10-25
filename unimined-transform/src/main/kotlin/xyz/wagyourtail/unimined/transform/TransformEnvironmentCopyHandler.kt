package xyz.wagyourtail.unimined.transform

import org.gradle.api.GradleException
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyHandler
import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyRequest
import xyz.wagyourtail.unimined.api.environment.EnvironmentReference
import xyz.wagyourtail.unimined.api.environment.EnvironmentSection

/** Copies transform registrations between environments. */
internal class TransformEnvironmentCopyHandler(
    private val project: Project
) : EnvironmentCopyHandler {

    override val sections: Set<EnvironmentSection> = setOf(EnvironmentSection.TRANSFORMS)

    override fun registerCopy(request: EnvironmentCopyRequest) {
        if (EnvironmentSection.TRANSFORMS !in request.sections) return
        extension(request.target.projectPath)
            .sourceSet(request.target.environmentId)
            .inheritFrom(request.source) {
                extension(request.source.projectPath)
                    .sourceSet(request.source.environmentId)
            }
    }

    private fun extension(projectPath: String): TransformExtension {
        val owner = if (projectPath == project.path) {
            project
        } else {
            project.project(projectPath)
        }
        return owner.extensions.findByType(TransformExtension::class.java)
            ?: throw GradleException(
                "Project '${owner.path}' does not have a Unimined transform extension; " +
                    "apply the transform or frontend plugin before sharing transforms"
            )
    }

}
