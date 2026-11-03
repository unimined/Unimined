package xyz.wagyourtail.unimined.api.environment

import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * Per-project registry connecting the `copyFrom`/`combineWith` DSL to the
 * component plugins that own each environment section.
 */
object EnvironmentCopyRegistry {

    private const val REQUESTS_KEY =
        "xyz.wagyourtail.unimined.environmentCopyRequests"
    private const val HANDLERS_KEY =
        "xyz.wagyourtail.unimined.environmentCopyHandlers"

    /**
     * Record [request] and pass it to already-registered handlers. Handlers
     * registered later replay pending requests.
     *
     * Set [requireHandlers] when the user explicitly requested a section that
     * must fail if no installed component can copy it.
     */
    @Synchronized
    fun record(
        targetProject: Project,
        request: EnvironmentCopyRequest,
        requireHandlers: Boolean = false
    ) {
        if (requireHandlers) {
            val handled = handlers(targetProject).flatMap { it.sections }.toSet()
            val unsupported = request.sections - handled
            if (unsupported.isNotEmpty()) {
                throw GradleException(
                    "No Unimined component handles environment sections " +
                            unsupported.joinToString { it.name } +
                            " in project '${targetProject.path}'"
                )
            }
        }

        requests(targetProject).add(request)
        handlers(targetProject).forEach { handler ->
            if (handler.sections.any(request.sections::contains)) {
                handler.registerCopy(request)
            }
        }
    }

    /** Register a component handler and replay all pending requests for it. */
    @Synchronized
    fun registerHandler(project: Project, handler: EnvironmentCopyHandler) {
        handlers(project).add(handler)
        requests(project).forEach { request ->
            if (handler.sections.any(request.sections::contains)) {
                handler.registerCopy(request)
            }
        }
    }

    /** Sections currently handled in [project]. */
    @Synchronized
    fun handledSections(project: Project): Set<EnvironmentSection> =
        handlers(project).flatMap { it.sections }.toSet()

    @Suppress("UNCHECKED_CAST")
    private fun requests(project: Project): MutableList<EnvironmentCopyRequest> {
        val extra = project.extensions.extraProperties
        if (!extra.has(REQUESTS_KEY)) {
            extra.set(REQUESTS_KEY, mutableListOf<EnvironmentCopyRequest>())
        }
        return extra.get(REQUESTS_KEY) as MutableList<EnvironmentCopyRequest>
    }

    @Suppress("UNCHECKED_CAST")
    private fun handlers(project: Project): MutableList<EnvironmentCopyHandler> {
        val extra = project.extensions.extraProperties
        if (!extra.has(HANDLERS_KEY)) {
            extra.set(HANDLERS_KEY, mutableListOf<EnvironmentCopyHandler>())
        }
        return extra.get(HANDLERS_KEY) as MutableList<EnvironmentCopyHandler>
    }

}
