package xyz.wagyourtail.unimined.remap

import org.gradle.api.tasks.Input
import xyz.wagyourtail.unimined.remap.spi.StepSpec
import org.gradle.api.tasks.Optional

/**
 * Spec for the `remap-access` step. Rewrites access-modification resources
 * embedded in the jar so they refer to the target namespace after a
 * namespace-changing `remap` step.
 *
 * [formats] selects the resource grammars to process (`"aw"`, `"at"`,
 * `"ct"`). [paths] may name additional jar entries containing one of those
 * grammars when loader metadata declares a non-standard path.
 */
class AccessRemapStepSpec(
    @get:Input @get:Optional override val engine: String? = null
) : StepSpec {

    /** Enabled resource formats: `aw`, `at`, and/or `ct`. */
    @get:Input
    var formats: Set<String> = setOf("aw", "at", "ct")

    /** Additional jar-entry paths to inspect for access metadata. */
    @get:Input
    var paths: Set<String> = emptySet()

    /**
     * AT files use the legacy `cls.member(desc)` syntax (forge
     * 1.3–1.6.4) — they round-trip through the legacy reader/writer so
     * era runtimes can still parse them.
     */
    @get:Input
    var legacyAt: Boolean = false

    /**
     * Namespace renames consulted when an embedded AW/CT file's declared
     * namespace is absent from the mapping tree — e.g. OSL classTweakers
     * say `intermediary` while an Ornithe environment's calamus column is
     * `calamusGen2`. Values also prefix-match tree columns, so wiring can
     * bind `intermediary` to the loader's prod channel name.
     */
    @get:Input
    var namespaceAliases: Map<String, String> = emptyMap()

    /**
     * Loader-facing labels for the *output* header's namespace column,
     * keyed by target-namespace name (prefix-matched for gen-suffixed
     * columns like `featherGen2`). Fabric-family runtimes only accept
     * canonical names (`named`, `intermediary`, `official`,
     * `clientOfficial`/`serverOfficial`) — the environment's internal
     * column names must be rewritten before launch. Unmapped targets
     * emit their own name unchanged.
     */
    @get:Input
    var namespaceLabels: Map<String, String> = emptyMap()

    /**
     * Canonical label for the environment's *dev* namespace, derived
     * from the mapping tree at execution (`namedNamespaces` hit, else
     * the last column — the same heuristic as
     * `MinecraftExtension.devNamespace`). A user-declared
     * `env.devNamespace` lands in [namespaceLabels] directly instead;
     * this field exists so wiring never resolves mapping data at
     * configuration time.
     */
    @get:Input @get:Optional
    var devNamespaceLabel: String? = null

    /**
     * Canonical names that alias to the environment's *dev* namespace
     * on the input side — embedded AW/CT files may declare `named`
     * (unimined's own emit, loom-dev artifacts) while the tree's dev
     * column is `mojmap`/`feather` under the hood. The dev column name
     * is derived at execution with the same heuristic as
     * [devNamespaceLabel], so wiring stays free of mapping resolution.
     * The raw column name needs no alias — it's a tree column and
     * matches directly.
     */
    @get:Input
    var devNamespaceAliases: Set<String> = emptySet()

}
