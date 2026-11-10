package xyz.wagyourtail.unimined.runs.internal

import xyz.wagyourtail.unimined.api.spec.RunConfigSpec

/**
 * Mutable [RunConfigSpec] presented to transform run mutators.
 * Public (despite the `internal` package) so `unimined-ide` can
 * reproduce the same run resolution `RunsWiring` performs when
 * generating IDE launch files.
 */
class MutableRunConfigSpec(
    override val name: String,
    override val prod: Boolean
) : RunConfigSpec {

    override var mainClass: String? = null

    override val javaagents: MutableList<java.nio.file.Path> = mutableListOf()

    override val jvmArgs: MutableList<String> = mutableListOf()

    override val programArgs: MutableList<String> = mutableListOf()

    override val environment: MutableMap<String, String> = mutableMapOf()

    override val properties: MutableMap<String, () -> String> = mutableMapOf()

}
