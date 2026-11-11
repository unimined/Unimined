package xyz.wagyourtail.unimined.api.util

import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware

/**
 * Extension nesting under the `unimined` facade.
 *
 * Each sub-plugin registers its extension top-level under a
 * namespaced name — `uniminedMinecraft`, `uniminedRemap`, … — so no
 * plugin squats generic words like `minecraft`/`runs`/`mapping`. The
 * top-level registration is the type-lookup anchor: every
 * `project.extensions.getByType(X)` callsite (internal wiring and
 * third-party plugins) keeps working regardless of name.
 *
 * When the frontend `unimined` extension exists, the same instance is
 * also exposed as a nested child — `unimined.minecraft` — via
 * [uniminedAdopt], so the facade hierarchy is a real extension tree.
 * One object, two views; never a second instance.
 */

/** The `unimined` extension as a child-extension host, or null when
 *  the frontend plugin isn't applied. Looked up by name + interface
 *  so sub-plugins don't depend on the frontend's extension class. */
fun Project.uniminedHost(): ExtensionAware? =
    extensions.findByName("unimined") as? ExtensionAware

/** Nested-or-top-level extension lookup: `unimined`'s container
 *  first, then the project's. Null when neither holds it. */
fun <T : Any> Project.uniminedExtOrNull(type: Class<T>): T? =
    uniminedHost()?.extensions?.findByType(type)
        ?: extensions.findByType(type)

/** Expose this plugin's already-registered top-level extension as
 *  [nestedName] under `unimined` when the frontend is present. No-op
 *  without a host or when already nested.
 *
 *  Call sites: once directly (covers frontend-applied-first), plus
 *  `pluginManager.withPlugin("xyz.wagyourtail.unimined")` (covers the
 *  sub-plugin-applied-first order). */
fun <T : Any> Project.uniminedAdopt(nestedName: String, type: Class<T>) {
    val host = uniminedHost() ?: return
    if (host.extensions.findByType(type) != null) return
    extensions.findByType(type)?.let { host.extensions.add(nestedName, it) }
}
