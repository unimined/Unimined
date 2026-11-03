package xyz.wagyourtail.unimined.init

/** Emits canonical 2.0 build snippets into `docs/snippets/` — docs
 *  pages include them via `--8<--` so examples track the generator.
 *  `java DocsGen <outDir>` (Gradle task `genDocSnippets`). */
fun main(args: Array<String>) {
    val out = args[0]
    fun put(name: String, s: String) =
        Io.writeText(Io.path(out, name), s.trimIndent().trim() + "\n")

    val base = InitSpec(
        group = "com.example", name = "examplemod", mcVersion = "1.20.1",
        loaders = listOf("fabric" to "0.16.0"), pluginVersion = "2.0.0-SNAPSHOT",
        mappings = listOf("yarn(10)"),
    )

    put("flat-fabric.gradle.kts", Templates.build(base))
    put("settings.gradle.kts", Templates.settings(base.copy(dsl = InitSpec.Dsl.KOTLIN)))

    val multi = base.copy(loaders = listOf(
        "fabric" to "0.16.0", "forge" to "47.2.0"),
        layout = InitSpec.Layout.MULTILOADER)
    put("multiloader-settings.gradle.kts", Templates.settings(multi))
    put("multiloader-fabric.gradle.kts",
        Templates.multiloaderModule(multi, "fabric"))

    put("product-bta.gradle.kts", Templates.buildProduct(base.copy(
        mcVersion = "b1.7.3", loaders = listOf("bta-babric" to "1.7.6"),
        layout = InitSpec.Layout.CUSTOM_PRODUCT, product = "bta")))

    put("mod-metadata.gradle.kts", Templates.build(base.copy(
        metadataFromDsl = true)).substringAfter("unimined {"))
}
