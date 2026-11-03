package xyz.wagyourtail.unimined.init


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

    /** A file the generator will write — text or embedded template bytes. */
    sealed class Render {
        abstract val executable: Boolean

        data class Text(
            val content: String,
            override val executable: Boolean = false
        ) : Render()

        data class Resource(
            val templatePath: String,
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
            when (spec.layout) {
                InitSpec.Layout.MULTILOADER -> {
                    put("settings.gradle$scriptSuffix",
                        Render.Text(Templates.multiloaderSettings(spec)))
                    put("common/build.gradle$scriptSuffix",
                        Render.Text(Templates.multiloaderModule(spec, null)))
                    for ((loader, _) in spec.loaders) {
                        put("$loader/build.gradle$scriptSuffix",
                            Render.Text(Templates.multiloaderModule(spec, loader)))
                    }
                }
                InitSpec.Layout.CUSTOM_PRODUCT -> {
                    put("settings.gradle$scriptSuffix",
                        Render.Text(Templates.settings(spec)))
                    put("build.gradle$scriptSuffix",
                        Render.Text(Templates.buildProduct(spec)))
                }
                else -> {
                    put("settings.gradle$scriptSuffix",
                        Render.Text(Templates.settings(spec)))
                    put("build.gradle$scriptSuffix",
                        Render.Text(Templates.build(spec)))
                }
            }
            put("gradle.properties", Render.Text(Templates.gradleProperties(spec)))
            put(".gitignore", Render.Text(Templates.gitignore))
            if (!spec.noWrapper) {
                put("gradlew", Render.Resource("/templates/gradlew", executable = true))
                put("gradlew.bat", Render.Resource("/templates/gradlew.bat"))
                put("gradle/wrapper/gradle-wrapper.properties",
                    Render.Text(Templates.wrapperProperties(spec)))
                put("gradle/wrapper/gradle-wrapper.jar",
                    Render.Resource("/templates/gradle-wrapper.jar"))
            }

            // multi-loader emits the §10 per-source-set layout —
            // src/<loader>/{java,resources} bound via sourceSet("<loader>")
            // MULTILOADER places entrypoints inside each module dir
            val pkg = spec.basePackage
            val multi = spec.loaders.size > 1 &&
                    spec.layout != InitSpec.Layout.MULTILOADER
            // no loaders (bare product env / vanilla): no entrypoint —
            // there is no loader API to implement
            for ((loader, _) in spec.loaders) {
                // a MULTILOADER module has a single env — it lives on
                // main inside the module dir; FLAT multi-loader uses
                // per-loader source sets instead
                val dir = if (spec.layout == InitSpec.Layout.MULTILOADER)
                    "$loader/" else ""
                val ss = if (multi) loader else "main"
                if (spec.jarmod) {
                    // jarmod-era: per-side source sets + transform stubs
                    for (side in Templates.jarSides(spec)) {
                        val cls = "${Templates.mainClassName(spec)}${
                            side.replaceFirstChar(Char::uppercase)}"
                        put("${dir}src/$side/java/${pkg.replace('.', '/')}/$cls.java",
                            Render.Text(Templates.mainClass(spec, loader)
                                .replace("${Templates.mainClassName(spec)}", cls)))
                        put("${dir}src/$side/resources/${spec.modId}-$side.transform",
                            Render.Text("$pkg.$cls\n"))
                    }
                } else {
                    put("${dir}src/$ss/java/${pkg.replace('.', '/')}/${Templates.mainClassName(spec)}.java",
                        Render.Text(Templates.mainClass(spec, loader)))
                }
                // static files only when metadata isn't DSL-generated
                if (!spec.metadataFromDsl) {
                    for ((path, content) in Templates.metadata(spec, loader)) {
                        put("${dir}src/$ss/resources/$path", Render.Text(content))
                    }
                }
            }
        }

    /**
     * Render every file under [outDir]. Refuses to overwrite an
     * existing non-empty directory unless [overwrite] is set.
     * Returns the emitted paths.
     */
    /** Extract `testing/<name>/` from a templates zip (plan §13 —
     *  JVM-only; Native throws via [extractFromZip]). */
    private fun extractTemplate(
        spec: InitSpec, outDir: String, overwrite: Boolean
    ): List<String> {
        val zip = spec.templatesZip ?: spec.templatesUrl?.let { url ->
            val tmp = Io.path(outDir, ".unimined-templates.zip")
            Io.writeBytes(tmp, httpGet(url))
            tmp
        } ?: error("templateName set but no templatesZip/templatesUrl")
        val written = extractFromZip(
            zip, "testing/${spec.templateName}/", outDir, overwrite)
        Io.delete(Io.path(outDir, ".unimined-templates.zip"))
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
    internal var wrapperDownloader: (version: String, outDir: String) -> Unit =
        ::defaultWrapperDownload

    private fun defaultWrapperDownload(version: String, outDir: String) {
        val base = "https://raw.githubusercontent.com/gradle/gradle/v$version"
        val files = mapOf(
            "gradlew" to "$base/gradlew",
            "gradlew.bat" to "$base/gradlew.bat",
            "gradle/wrapper/gradle-wrapper.jar" to
                    "$base/gradle/wrapper/gradle-wrapper.jar",
        )
        for ((rel, url) in files) {
            val target = Io.path(outDir, rel)
            Io.writeBytes(target, httpGet(url))
            if (rel.endsWith(".bat")) ensureCrlf(target)
        }
        setExecutable(Io.path(outDir, "gradlew"))
    }

    /** Rewrite [file] with CRLF line endings — Windows cmd mis-parses
     *  LF-only batch files (labels/`goto`), and raw.githubusercontent
     *  serves LF-stored blobs. */
    private fun ensureCrlf(file: String) {
        val text = Io.readText(file)
            .replace("\r\n", "\n").replace("\r", "\n")
        Io.writeText(file, text.replace("\n", "\r\n"))
    }

    /**
     * Render every file under [outDir]. Refuses to overwrite an
     * existing non-empty directory unless [overwrite] is set.
     * Returns the emitted paths.
     */
    fun generate(spec: InitSpec, outDir: String,
                 overwrite: Boolean = false): List<String> {
        require(overwrite || !Io.exists(outDir) || Io.isEmptyDir(outDir)) {
            "output directory $outDir is not empty (pass overwrite=true)"
        }
        if (spec.templateName != null) {
            return extractTemplate(spec, outDir, overwrite)
        }
        // wrapper: download the real files for the target version,
        // bundled resources are the offline fallback
        val downloaded = !spec.noWrapper && try {
            wrapperDownloader(spec.wrapperGradleVersion, outDir); true
        } catch (e: Exception) {
            false
        }
        val written = if (downloaded) {
            wrapperRemote.mapTo(mutableListOf()) { Io.path(outDir, it) }
        } else mutableListOf()
        for ((rel, render) in files(spec)) {
            if (downloaded && rel in wrapperRemote) continue
            val target = Io.path(outDir, rel)
            when (render) {
                is Render.Text -> Io.writeText(target, render.content)
                is Render.Resource ->
                    Io.writeBytes(target, TemplateFiles[render.templatePath])
            }
            if (rel.endsWith(".bat")) ensureCrlf(target)
            if (render.executable) setExecutable(target)
            written.add(target)
        }
        return written
    }

}
