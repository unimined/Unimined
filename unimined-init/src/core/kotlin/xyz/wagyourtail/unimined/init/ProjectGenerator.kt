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

            val pkg = spec.basePackage
            val srcDir = "src/main/java/" + pkg.replace('.', '/')
            for ((loader, _) in spec.loaders) {
                put("$srcDir/${Templates.mainClassName(spec)}.java",
                    Render.Text(Templates.mainClass(spec, loader)))
                for ((path, content) in Templates.metadata(spec, loader)) {
                    put("src/main/resources/$path", Render.Text(content))
                }
            }
        }

    /**
     * Render every file under [outDir]. Refuses to overwrite an
     * existing non-empty directory unless [overwrite] is set.
     * Returns the emitted paths.
     */
    fun generate(spec: InitSpec, outDir: Path, overwrite: Boolean = false): List<Path> {
        if (Files.exists(outDir) && !overwrite) {
            Files.list(outDir).use { stream ->
                require(!stream.findAny().isPresent) {
                    "output directory $outDir is not empty (pass overwrite=true)"
                }
            }
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
