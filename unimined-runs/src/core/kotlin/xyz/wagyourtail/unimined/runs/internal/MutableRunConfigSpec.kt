package xyz.wagyourtail.unimined.runs.internal

import xyz.wagyourtail.unimined.api.spec.RunConfigSpec

/** Mutable [RunConfigSpec] presented to transform run mutators. */
internal class MutableRunConfigSpec(override val name: String) : RunConfigSpec {

    override var mainClass: String? = null

    override val jvmArgs: MutableList<String> = mutableListOf()

    override val programArgs: MutableList<String> = mutableListOf()

    override val environment: MutableMap<String, String> = mutableMapOf()

    override val properties: MutableMap<String, () -> String> = mutableMapOf()

}
