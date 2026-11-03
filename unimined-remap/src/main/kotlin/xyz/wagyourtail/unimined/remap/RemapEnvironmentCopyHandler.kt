package xyz.wagyourtail.unimined.remap

import org.gradle.api.GradleException
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyHandler
import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyRequest
import xyz.wagyourtail.unimined.api.environment.EnvironmentReference
import xyz.wagyourtail.unimined.api.environment.EnvironmentSection

/** Copies remap surface pipelines between environments. */
internal class RemapEnvironmentCopyHandler(
    private val project: Project
) : EnvironmentCopyHandler {

    override val sections: Set<EnvironmentSection> = setOf(EnvironmentSection.REMAP)

    override fun registerCopy(request: EnvironmentCopyRequest) {
        if (EnvironmentSection.REMAP !in request.sections) return
        target(request.target).inheritFrom(request.source) {
            source(request.source).sourceSet(request.source.environmentId)
        }
    }

    private fun target(reference: EnvironmentReference): RemapEnvironmentSpec =
        extension(reference.projectPath).sourceSet(reference.environmentId)

    private fun source(reference: EnvironmentReference): RemapExtension =
        extension(reference.projectPath)

    private fun extension(projectPath: String): RemapExtension {
        val owner = if (projectPath == project.path) {
            project
        } else {
            project.project(projectPath)
        }
        return owner.extensions.findByType(RemapExtension::class.java)
            ?: throw GradleException(
                "Project '${owner.path}' does not have a Unimined remap extension; " +
                        "apply the remap or frontend plugin before sharing remap configuration"
            )
    }

}
