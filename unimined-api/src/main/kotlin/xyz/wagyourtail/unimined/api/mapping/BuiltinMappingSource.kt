package xyz.wagyourtail.unimined.api.mapping

/**
 * A declared source naming a provider/builtin mapping channel — e.g.
 * `official()`, `mojmap()`, `searge()`. Materialization is owned by the
 * environment's Minecraft provider channel machinery.
 */
interface BuiltinMappingSource : MappingSource {

    /** The channel name declared in the DSL. */
    val channel: String

    /**
     * Channel parameters declared at the DSL site — e.g. the MCPConfig
     * version for `searge(version)` — resolved lazily at materialization
     * so environment defaults (Minecraft version) need not be set yet.
     */
    val params: Map<String, org.gradle.api.provider.Provider<String>>
        get() = emptyMap()

}
