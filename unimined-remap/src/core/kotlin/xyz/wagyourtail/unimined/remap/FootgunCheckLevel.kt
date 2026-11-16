package xyz.wagyourtail.unimined.remap

/**
 * Post-remap validation level for production jars — packaging footguns
 * compile fine and only fail at runtime: metadata keys pointing at files
 * never packed, entrypoint/mixin/main classes the jar doesn't contain,
 * a missing or wrong remapped-namespace manifest marker.
 */
enum class FootgunCheckLevel {

    /** Skip validation entirely. */
    OFF,

    /** Report findings as warnings; the build continues. */
    WARN,

    /** Fail the task on any finding. */
    ERROR,
}
