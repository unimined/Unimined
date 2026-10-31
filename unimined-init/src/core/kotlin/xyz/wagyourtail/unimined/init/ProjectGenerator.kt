package xyz.wagyourtail.unimined.init

import java.nio.file.Files
import java.nio.file.Path

/**
 * Renders a complete, `gradlew build`-able mod project from an
 * [InitSpec]. Pure library — no Gradle imports, so it is reusable from
 * the CLI, tests, and any future IDE/frontend.
 *
 * Emitted files (single-loader flat layout; the multi-loader
 * per-source-set layout lands as a follow-up):
 * `settings.gradle[.kts]`, `build.gradle[.kts]`, `gradle.properties`,
 * `.gitignore`, `gradlew[.bat]` + `gradle/wrapper/` files (jar included —
 * issue #160), `src/` skeleton, and loader metadata stubs.
 */
object ProjectGenerator {

    /** A file the generator will write — text or classpath resource. */
    sealed class Render {
        abstract val executable: Boolean

        data class Text(
            val content: String,
            override val executable: Boolean = false
        ) : Render()

        data class Resource(
            val resourcePath: String,
            override val executable: Boolean = false
        ) : Render()
    }

    /**
     * The full file map for [spec] — relative path → render source.
     * Pure/deterministic so tests can assert on it without a filesystem.
     */
    fun files(spec: InitSpec): Map<String, Render> =
        linkedMapOf<String, Render>().apply {
            val kts = spec.dsl == InitSpec.Dsl.KOTLIN
            val sfx = spec.dsl.scriptSuffix
            val scriptSuffix = if (kts) ".kts" else ""
            put("settings.gradle$scriptSuffix", Render.Text(Templates.settings(spec)))
            put("build.gradle$scriptSuffix", Render.Text(Templates.build(spec)))
            put("gradle.properties", Render.Text(Templates.gradleProperties(spec)))
            put(".gitignore", Render.Text(Templates.gitignore))
            put("gradlew", Render.Resource("/templates/gradlew", executable = true))
            put("gradlew.bat", Render.Resource("/templates/gradlew.bat"))
            put("gradle/wrapper/gradle-wrapper.properties",
                Render.Text(Templates.wrapperProperties(spec)))
            put("gradle/wrapper/gradle-wrapper.jar",
                Render.Resource("/templates/gradle-wrapper.jar"))

            // multi-loader emits the §10 per-source-set layout —
            // src/<loader>/{java,resources} bound via sourceSet("<loader>")
            val pkg = spec.basePackage
            val multi = spec.loaders.size > 1
            for ((loader, _) in spec.loaders) {
                val ss = if (multi) loader else "main"
                put("src/$ss/java/${pkg.replace('.', '/')}/${Templates.mainClassName(spec)}.java",
                    Render.Text(Templates.mainClass(spec, loader)))
                for ((path, content) in Templates.metadata(spec, loader)) {
                    put("src/$ss/resources/$path", Render.Text(content))
                }
            }
        }

    /**
     * Render every file under [outDir]. Refuses to overwrite an
     * existing non-empty directory unless [overwrite] is set.
     * Returns the emitted paths.
     */
    /** Extract `testing/<name>/` from a templates zip (plan §13). */
    private fun extractTemplate(
        spec: InitSpec, outDir: Path, overwrite: Boolean
    ): List<Path> {
        val zip = spec.templatesZip
            ?: error("templateName set but no templatesZip")
        val prefix = "testing/${spec.templateName}/"
        val written = mutableListOf<Path>()
        java.util.zip.ZipFile(zip.toFile()).use { zf ->
            zf.entries().asSequence()
                .filter { it.name.startsWith(prefix) && !it.isDirectory }
                .forEach { e ->
                    val rel = e.name.removePrefix(prefix)
                    val target = outDir.resolve(rel).normalize()
                    require(target.startsWith(outDir)) {
                        "template entry escapes outDir: ${e.name}"
                    }
                    if (Files.exists(target) && !overwrite) {
                        error("refusing to overwrite $target")
                    }
                    target.parent?.let { Files.createDirectories(it) }
                    zf.getInputStream(e).use { input ->
                        Files.copy(input, target,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                    written.add(target)
                }
        }
        require(written.isNotEmpty()) {
            "template '${spec.templateName}' not found in $zip"
        }
        return written
    }

    fun generate(spec: InitSpec, outDir: Path, overwrite: Boolean = false): List<Path> {
        if (Files.exists(outDir) && !overwrite) {
            Files.list(outDir).use { stream ->
                require(!stream.findAny().isPresent) {
                    "output directory $outDir is not empty (pass overwrite=true)"
                }
            }
        }
        if (spec.templateName != null) {
            return extractTemplate(spec, outDir, overwrite)
        }
        val written = mutableListOf<Path>()
        for ((rel, render) in files(spec)) {
            val target = outDir.resolve(rel)
            Files.createDirectories(target.parent)
            when (render) {
                is Render.Text -> Files.writeString(target, render.content)
                is Render.Resource -> javaClass.getResourceAsStream(render.resourcePath)
                    ?.use { Files.copy(it, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                    ?: error("missing template resource ${render.resourcePath}")
            }
            if (render.executable) {
                target.toFile().setExecutable(true)
            }
            written.add(target)
        }
        return written
    }

}
