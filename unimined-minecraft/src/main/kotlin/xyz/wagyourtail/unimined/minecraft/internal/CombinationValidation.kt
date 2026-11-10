package xyz.wagyourtail.unimined.minecraft.internal

import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension

/**
 * Cross-environment `combineWith` validation for [MinecraftExtension] —
 * version, loader, and dev-namespace compatibility between a combined
 * environment and its sources, plus cycle rejection. Lives on the
 * extension (not the environment) because the checks span project
 * boundaries: a source reference can point at another project's env.
 */
internal class CombinationValidation(private val ext: MinecraftExtension) {

    private val project = ext.project

    /** Reentrancy guard — validation can recurse through source refs
     *  that resolve a target env's spec chain; a target already
     *  mid-validation contributes no new constraints. */
    private val validatingCombinations = ThreadLocal<MutableSet<String>>()

    /** Validate version, loader, and dev-namespace compatibility for combined outputs. */
    fun validate(environment: MinecraftEnvironment) {
        if (environment.combinedWith.isEmpty()) return
        val targetKey = "${project.path}:${environment.environmentId}"
        val validating = validatingCombinations.get() ?: mutableSetOf<String>().also {
            validatingCombinations.set(it)
        }
        if (!validating.add(targetKey)) return
        try {
            rejectCombinationCycles(environment, linkedSetOf(targetKey))
            for (sourceReference in environment.combinedWith) {
                val source = ext.environment(sourceReference)
                // unset on either side = no constraint — an implicit
                // parent (often unconfigured `main`) must not hard-fail
                // the check; matches checkVersionConflicts + the loader
                // guard below
                val sourceVersion = source.version.orNull
                val targetVersion = environment.version.orNull
                if (sourceVersion != null && targetVersion != null &&
                    sourceVersion != targetVersion) {
                    throw GradleException(
                        "Cannot combine '${project.path}:${environment.environmentId}' " +
                                "with '${sourceReference.projectPath}:${sourceReference.environmentId}': " +
                                "Minecraft versions differ ($targetVersion != $sourceVersion)"
                    )
                }

                val targetLoader = environment.loaderSpec.orNull?.name
                val sourceLoader = source.loaderSpec.orNull?.name
                if (targetLoader != null && sourceLoader != null && targetLoader != sourceLoader) {
                    throw GradleException(
                        "Cannot combine '${project.path}:${environment.environmentId}' " +
                                "with '${sourceReference.projectPath}:${sourceReference.environmentId}': " +
                                "loaders differ ($targetLoader != $sourceLoader)"
                    )
                }

                val targetNamespace = devNamespace(project.path, environment.environmentId)
                val sourceNamespace = devNamespace(
                    sourceReference.projectPath,
                    sourceReference.environmentId
                )
                if (targetNamespace != null && sourceNamespace != null &&
                    targetNamespace != sourceNamespace
                ) {
                    throw GradleException(
                        "Cannot combine '${project.path}:${environment.environmentId}' " +
                                "with '${sourceReference.projectPath}:${sourceReference.environmentId}': " +
                                "dev namespaces differ ($targetNamespace != $sourceNamespace) — " +
                                "set the same devNamespace on both environments, or leave it " +
                                "unset to inherit the loader default"
                    )
                }
            }
        } finally {
            validating.remove(targetKey)
        }
    }

    private fun rejectCombinationCycles(
        environment: MinecraftEnvironment,
        path: LinkedHashSet<String>
    ) {
        for (sourceReference in environment.combinedWith) {
            val key = "${sourceReference.projectPath}:${sourceReference.environmentId}"
            if (!path.add(key)) {
                throw GradleException(
                    "Cyclic Unimined source-set combination involving " +
                            path.joinToString(" -> ") + " -> " + key
                )
            }
            rejectCombinationCycles(ext.environment(sourceReference), path)
            path.remove(key)
        }
    }

    private fun devNamespace(projectPath: String, environmentId: String): String? {
        val sourceProject = if (projectPath == project.path) {
            project
        } else {
            project.project(projectPath)
        }
        val extension = sourceProject.extensions.getByType(MinecraftExtension::class.java)
        // dev-ns resolves the composed mapping tree — when this check
        // runs inside the target env's own resolution (validate lives
        // inside resolvedMappingData), the re-entrant call short-
        // circuits to an empty view and callers fall back to the
        // provider's `official` tree — a false mismatch, not a
        // CircularEvaluationException. Mid-eval = unknown → no
        // constraint, matching the existing contract.
        if (extension.resolution.isResolving(environmentId)) return null
        return try {
            // named-aware dev ns — `namespaces.lastOrNull()` is order-
            // dependent: inherited/default mapping hops can reorder the
            // composed tree so `official` lands last even though the env
            // develops in `named` (gametest/datagen aux envs hit this).
            extension.devNamespace(environmentId).orNull
        } catch (e: org.gradle.internal.evaluation.CircularEvaluationException) {
            // resolving the mapping tree can re-enter this env's
            // provider chain (deferred sources read `obfuscated`,
            // which resolves spec() → validateCombinations → here).
            // Mid-eval = namespace unknown → no constraint; the check
            // still runs on the canonical spec() call path.
            null
        }
    }
}
