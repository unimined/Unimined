package xyz.wagyourtail.unimined.remap

import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection

/**
 * Result of [RemapExtension.remappedViews] or
 * [RemapExtension.remappedViewsFor].
 *
 * [views] exposes the generated namespace-specific resolvable configurations;
 * the object itself is a [FileCollection] containing all of them, so it can be
 * passed directly to `classpath.from(...)`, a task input, or another file
 * collection.
 */
class RemappedViewSet internal constructor(
    /** Base name supplied to the DSL. */
    val name: String,
    /** Generated views keyed by their declared source namespace. */
    val views: Map<String, Configuration>,
    delegate: FileCollection
) : FileCollection by delegate
