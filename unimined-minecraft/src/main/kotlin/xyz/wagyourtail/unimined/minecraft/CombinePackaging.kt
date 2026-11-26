package xyz.wagyourtail.unimined.minecraft

/**
 * Packaging vocabulary for a `combineWith` edge — how the combined
 * environment's output enters (or does not) the composed environment's
 * packaged artifact.
 *
 * This enum is internal vocabulary, not DSL surface: build scripts
 * write the modes as case-insensitive strings
 * (`combineWith(sourceSets.main, "none")` or
 * `combineWith("main") { packaging = "none" }`), validated lazily at
 * the point of use with errors listing [DSL_NAMES]. The typed enum
 * exists for programmatic consumers and the wiring itself.
 */
enum class CombinePackaging {

    /** Combined output embeds flat in the composed env's artifact —
     *  the default, matching Unimined's behavior before packaging
     *  modes existed (shared mod code ships inside the env jar). */
    SHADOW,

    /** Compile/runtime/environment composition only — the combined
     *  output stays on the classpaths and section copies still apply,
     *  but the packaged artifact (dev jar and production remap input)
     *  carries the env's own output alone. The chimera/merge
     *  composition mode: era jars stay disjoint so an outer merge
     *  helper can assemble the multi-everything artifact without
     *  dedupe ambiguity. */
    NONE,

    /** Combined output nests inside the artifact as a jar with
     *  loader-specific nesting metadata. Declared vocabulary only —
     *  validation hard-errors "not yet implemented"; no behavior is
     *  stubbed. */
    JAR_IN_JAR;

    companion object {

        /** Canonical DSL spellings, in the order validation errors
         *  list them. */
        @JvmField
        val DSL_NAMES: List<String> = listOf("shadow", "none", "jarinjar")

        /** Parse a DSL mode string case-insensitively (`-`/`_`
         *  separators optional — `"jarinjar"`, `"jar-in-jar"` and
         *  `"jar_in_jar"` all spell [JAR_IN_JAR]); null when no value
         *  matches. Callers turn the null into the valid-values
         *  failure at their point of use. */
        fun parse(raw: String): CombinePackaging? =
            when (raw.lowercase().replace("-", "").replace("_", "")) {
                "shadow" -> SHADOW
                "none" -> NONE
                "jarinjar" -> JAR_IN_JAR
                else -> null
            }
    }
}
