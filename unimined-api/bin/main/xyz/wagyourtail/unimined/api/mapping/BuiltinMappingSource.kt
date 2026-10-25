package xyz.wagyourtail.unimined.api.mapping

/**
 * A declared source naming a provider/builtin mapping channel — e.g.
 * `official()`, `mojmap()`, `searge()`. Materialization is owned by the
 * environment's Minecraft provider channel machinery.
 */
interface BuiltinMappingSource : MappingSource {

    /** The channel name declared in the DSL. */
    val channel: String

}
