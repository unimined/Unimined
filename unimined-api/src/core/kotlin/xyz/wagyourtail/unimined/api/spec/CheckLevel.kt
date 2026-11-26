package xyz.wagyourtail.unimined.api.spec

/**
 * Strict-check enforcement level for Unimined's opt-out validations —
 * `remap { checks { jarLint } }`, `minecraft { checks { } }` and any
 * future named checks. These are the diagnostics that guard against
 * configurations that compile/wire fine but fail or misbehave later;
 * every one defaults to [ERROR].
 */
enum class CheckLevel {

    /** Skip the check entirely. */
    OFF,

    /** Report findings as warnings; the build continues. */
    WARN,

    /** Fail the build on any finding. */
    ERROR,
}
