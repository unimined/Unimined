package xyz.wagyourtail.unimined.minecraft

import org.gradle.api.provider.Property
import xyz.wagyourtail.unimined.api.spec.CheckLevel

/**
 * `uniminedMinecraft { checks { } }` (and `minecraft { checks { } }`
 * under the frontend) — enforcement levels for the strict environment
 * validations Unimined runs. Every check defaults to
 * [CheckLevel.ERROR]; `WARN` downgrades findings to warnings,
 * `OFF` skips the check.
 *
 * These cover the scope 1.x's `unimined.footgunChecks` Boolean gated —
 * checks on environment combinations — plus the version-consistency
 * validation on section inheritance.
 */
abstract class MinecraftChecksSpec {

    /**
     * Cross-environment `combineWith` consistency: a combined
     * environment must share its sources' Minecraft version, loader
     * family, and dev namespace — combining a yarn env with a
     * retroMCP env would merge classes that don't correspond.
     *
     * Combine-*cycle* rejection is not gated by this check — a cyclic
     * `combineWith` chain can't resolve at all, so it always fails.
     */
    abstract val combination: Property<CheckLevel>

    /**
     * Version consistency across section inheritance — an environment
     * that `combineWith`/`copyFrom`s sections out of another env must
     * declare the same Minecraft version (or leave it unset to
     * inherit).
     */
    abstract val versionConflict: Property<CheckLevel>

    init {
        combination.convention(CheckLevel.ERROR)
        versionConflict.convention(CheckLevel.ERROR)
    }
}
