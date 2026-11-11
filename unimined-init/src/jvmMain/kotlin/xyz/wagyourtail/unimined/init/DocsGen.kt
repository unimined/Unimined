package xyz.wagyourtail.unimined.init

/** Emits canonical 2.0 build snippets + registry tables into
 *  `docs/generated/` — pages `--8<--` them in so docs track the
 *  generator. `java DocsGen <outDir>` (task `genDocSnippets`). */
fun main(args: Array<String>) {
    val out = args[0]
    fun put(name: String, s: String) =
        Io.writeText(Io.path(out, name), s.trimIndent().trim() + "\n")

    val base = InitSpec(
        group = "com.example", name = "examplemod", mcVersion = "1.20.1",
        loaders = listOf("fabric" to "0.16.0"), pluginVersion = "2.0.0-SNAPSHOT",
        mappings = listOf("yarn(\"${runCatching {
            VersionResolver().latestLoader("yarn", "1.20.1")
        }.getOrDefault("1.20.1+build.10")}\")"),
    )

    /** Emits both DSL variants — `<name>.gradle.kts` + `<name>.gradle`
     *  (Groovy) — so pages can tab them via `--8<--` pairs. */
    fun putBoth(name: String, spec: InitSpec, render: (InitSpec) -> String) {
        put("$name.gradle.kts", render(spec))
        put("$name.gradle", render(spec.copy(dsl = InitSpec.Dsl.GROOVY)))
    }

    putBoth("flat-fabric", base, Templates::build)
    putBoth("settings", base, Templates::settings)
    // DSL-agnostic — shown alongside the build scripts it feeds
    put("gradle.properties", Templates.gradleProperties(base))

    // forge eras — same flag, different env shapes
    for ((tag, mc) in listOf("170" to "1.7.10", "112" to "1.12.2", "modern" to "1.20.1")) {
        val mcpVer = when {
            mc.startsWith("1.7") -> "mcp(\"stable\", \"12\")"
            mc == "1.12.2" -> "mcp(\"stable\", \"39\")"
            else -> "mojmap()"
        }
        putBoth("flat-forge-$tag", base.copy(
            mcVersion = mc, loaders = listOf("forge" to if (mc == "1.7.10") "10.13.4.1614"
            else if (mc == "1.12.2") "14.23.5.2860" else "47.2.0"),
            mappings = listOf(mcpVer)), Templates::build)
    }

    val multi = base.copy(loaders = listOf("fabric" to "0.16.0", "forge" to "47.2.0"),
        layout = InitSpec.Layout.MULTILOADER)
    putBoth("multiloader-settings", multi, Templates::multiloaderSettings)
    putBoth("multiloader-common", multi,
        { Templates.multiloaderModule(it, null) })
    putBoth("multiloader-fabric", multi,
        { Templates.multiloaderModule(it, "fabric") })
    putBoth("multiloader-forge", multi,
        { Templates.multiloaderModule(it, "forge") })

    putBoth("product-bta", base.copy(
        mcVersion = runCatching { VersionResolver().latestLoader("bta", "") }.getOrDefault("1.7.7.0_02"), loaders = listOf("bta-babric" to "1.7.6"),
        layout = InitSpec.Layout.CUSTOM_PRODUCT, product = "bta"),
        Templates::buildProduct)
    putBoth("product-custom", base.copy(
        loaders = listOf("fabric" to "0.16.0"),
        layout = InitSpec.Layout.CUSTOM_PRODUCT, product = "custom:mymodpack"),
        Templates::buildProduct)

    putBoth("side-server", base.copy(
        loaders = listOf("forge" to "47.2.0"), side = "server"),
        Templates::build)
    putBoth("jarmod", base.copy(
        mcVersion = "b1.7.3", loaders = emptyList(), jarmod = true),
        Templates::build)
    putBoth("jars-split", base.copy(splitJars = true), Templates::build)
    putBoth("jars-merged", base.copy(splitJars = false), Templates::build)

    putBoth("mod-metadata", base.copy(metadataFromDsl = true),
        { Templates.build(it).substringAfter("unimined {") })

    // registry tables — curated here (unimined-init can't see the
    // frontend registries); flag drift in code review.
    put("tables/loaders.md", """
        |## Loader registry
        |
        || Loader | Notes |
        ||---|---|
        || `fabric` | Fabric Loader |
        || `quilt` | Quilt loader |
        || `forge` | era-aware — FG1-era ≤1.7.10, 1.8–1.12, modern ≥1.13 |
        || `neoforge` | |
        || `cleanroom` | CleanroomMC |
        || `flint` | removed — maven.flintloader.net offline |
        || `rift` | DimDev's 1.13-era LaunchWrapper loader — versions MC-keyed (`org.dimdev:rift:<mc>`) |
        || `foxloader` | ReIndev's loader |
        || `liteloader` | |
        || `craftbukkit`, `spigot`, `paper` | server-side |
        || `fg1` | ForgeGradle 1 |
        || `jarmod` | pseudo-loader — jar-injected mods |
        || `accessTransformer`, `accessWidener` | transform-only |
        |""")

    put("tables/api-providers.md", """
        |## `loaderApi` providers
        |
        || Name | Artifact | depends modid |
        ||---|---|---|
        || `fabric` | `net.fabricmc.fabric-api:fabric-api` | `fabric-api` |
        || `quilt` | `org.quiltmc.quilted-fabric-api:quilted-fabric-api` | `qfab` |
        || `qsl` | `org.quiltmc:qsl` | `qsl` |
        || `legacyfabric` | `net.legacyfabric.legacy-fabric-api:legacy-fabric-api` | `legacy-fabric-api` |
        || `stationapi` | `net.modificationstation:StationAPI` (releases) | `stationapi` |
        || `stationapi-snapshots` | `net.modificationstation:StationAPI` (snapshots) | `stationapi` |
        || `osl` | `net.ornithemc:osl` — per-MC modules via meta.ornithemc.net | `osl` |
        |""")
}
