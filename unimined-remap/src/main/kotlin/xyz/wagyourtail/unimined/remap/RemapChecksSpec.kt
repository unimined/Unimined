package xyz.wagyourtail.unimined.remap

import org.gradle.api.provider.Property
import xyz.wagyourtail.unimined.api.spec.CheckLevel

/**
 * `remap { checks { } }` — enforcement levels for the strict
 * validations the remap module runs. Every check defaults to
 * [CheckLevel.ERROR]; `WARN` downgrades findings to warnings,
 * `OFF` skips the check.
 *
 * ```kotlin
 * remap {
 *     checks {
 *         jarLint = CheckLevel.WARN
 *     }
 * }
 * ```
 */
abstract class RemapChecksSpec {

    /**
     * Production-jar packaging lint on emitted artifacts
     * (`remapJar`, `remapArtifact` outputs): metadata keys pointing at
     * files never packed (access wideners, mixin configs, nested jars,
     * icons), entrypoint/main/mixin classes the jar doesn't contain,
     * and a missing or wrong `Unimined-Mapping-Namespace` marker.
     */
    abstract val jarLint: Property<CheckLevel>

    init {
        jarLint.convention(CheckLevel.ERROR)
    }
}
