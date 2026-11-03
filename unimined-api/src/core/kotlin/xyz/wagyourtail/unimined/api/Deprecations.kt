package xyz.wagyourtail.unimined.api

/**
 * Deprecation bridge (plan §Phase 8): 1.x-shaped DSL calls fail with a
 * migration message naming the 2.0 replacement — `@Deprecated` gives
 * the IDE warning, this gives the build-time one.
 */
object Deprecations {

    /** Throws an error describing `old` → `new` migration. */
    fun movedDsl(old: String, new: String): Nothing {
        throw IllegalStateException(
            "[unimined 2.0] `$old` was removed — use `$new` instead. " +
                    "See Docs/migration (1.x → 2.0) for the moved-DSL map."
        )
    }

    /** Loader patcher blocks became `loader("name", "version")`. */
    fun loaderBlock(loader: String): Nothing =
        movedDsl("$loader { }", "loader(\"$loader\", \"<version>\")")

}
