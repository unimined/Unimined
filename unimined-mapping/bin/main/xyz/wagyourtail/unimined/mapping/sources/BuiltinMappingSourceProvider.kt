package xyz.wagyourtail.unimined.mapping.sources

import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.mapping.BuiltinMappingSource
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingSource
import xyz.wagyourtail.unimined.api.mapping.MappingSourceProvider
import xyz.wagyourtail.unimined.api.mapping.MaterializedMappingSource

/**
 * Claims builtin/provider channels (`official()`, `mojmap()`, `searge()`)
 * so they fail with an actionable error until provider-native's manifest
 * machinery materializes them.
 */
class BuiltinMappingSourceProvider : MappingSourceProvider {

    override val name = "builtin"

    override fun supports(spec: MappingSource): Boolean = spec is BuiltinMappingSource

    override fun materialize(
        source: MappingSource,
        ctx: MappingContext
    ): MaterializedMappingSource {
        source as BuiltinMappingSource
        throw GradleException(
            "mappings { ${source.channel}() }: builtin channels resolve " +
                "through provider-native's manifest machinery — not yet " +
                "implemented; use file() or maven() meanwhile"
        )
    }

}
