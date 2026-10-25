package xyz.wagyourtail.unimined.mclauncher

/**
 * Version-JSON rule evaluation: an entry applies when no `disallow`
 * rule matches and either no `allow` rules exist or at least one does —
 * the launcher's documented semantics.
 */
object RuleSet {

    /** Evaluate [rules] for [os] with the enabled [features] set. */
    fun allowed(
        rules: List<VersionData.Rule>,
        os: OperatingSystem,
        features: Set<String>
    ): Boolean {
        if (rules.isEmpty()) return true
        var allowed = false
        for (rule in rules) {
            if (rule.matches(os, features)) allowed = rule.allow
        }
        return allowed
    }

}
