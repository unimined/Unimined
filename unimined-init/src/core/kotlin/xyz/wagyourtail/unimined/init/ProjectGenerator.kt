package xyz.wagyourtail.unimined.init

import java.net.URI
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
                // static files only when metadata isn't DSL-generated
                if (!spec.metadataFromDsl) {
                    for ((path, content) in Templates.metadata(spec, loader)) {
                        put("src/$ss/resources/$path", Render.Text(content))
                    }
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

    /**
     * Emitted-path set for the wrapper files — when [wrapperDownloader]
     * succeeds these bundled `Render.Resource` entries are skipped.
     */
    private val wrapperRemote = setOf(
        "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar"
    )

    /**
     * Fetches the real wrapper files for [version] from gradle/gradle
     * at tag `v<version>` — injected for tests (throw = offline path).
     */
    internal var wrapperDownloader: (version: String, outDir: Path) -> Unit =
        ::defaultWrapperDownload

    private fun defaultWrapperDownload(version: String, outDir: Path) {
        val base = "https://raw.githubusercontent.com/gradle/gradle/v$version"
        val files = mapOf(
            "gradlew" to "$base/gradlew",
            "gradlew.bat" to "$base/gradlew.bat",
            "gradle/wrapper/gradle-wrapper.jar" to
                    "$base/gradle/wrapper/gradle-wrapper.jar",
        )
        for ((rel, url) in files) {
            val target = outDir.resolve(rel)
            target.parent?.let { Files.createDirectories(it) }
            URI.create(url).toURL().openStream().use {
                Files.copy(it, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        outDir.resolve("gradlew").toFile().setExecutable(true)
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
        // wrapper: download the real files for the target version,
        // bundled resources are the offline fallback
        val downloaded = try {
            wrapperDownloader(spec.wrapperGradleVersion, outDir); true
        } catch (e: Exception) {
            false
        }
        val written = if (downloaded) {
            wrapperRemote.mapTo(mutableListOf()) { outDir.resolve(it) }
        } else mutableListOf()
        for ((rel, render) in files(spec)) {
            if (downloaded && rel in wrapperRemote) continue
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
