package xyz.wagyourtail.unimined.api.util

import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Detached-tool coordinate registry — `uniminedTools { }`.
 *
 * Every hardcoded tool coordinate Unimined resolves into a detached
 * configuration is surfaced here as a `Property<String>` holding the
 * full `g:a:v[:classifier][@ext]` notation, so users can override the
 * version (or fork) without forking the plugin:
 *
 * ```kotlin
 * uniminedTools {
 *     jst.set("net.neoforged.jst:jst-cli:2.1.0")
 * }
 * ```
 *
 * Registered lazily via [Project.uniminedTools] — any plugin module
 * can look it up without apply-order coupling. Properties carry
 * conventions, so reading them never triggers resolution.
 */
abstract class UniminedTools @Inject constructor(
    objects: ObjectFactory
) {

    /** JST CLI — enriches the `RemapSurface.SOURCES` pipeline on forge
     *  userdev so generated sources carry AT/interface-injection. */
    val jst: Property<String> = objects.property(String::class.java)
        .convention("net.neoforged.jst:jst-cli:2.0.11")

    /** `unprotect` — strips legacy-runtime protections before dev
     *  launch on forge-era environments. */
    val unprotect: Property<String> = objects.property(String::class.java)
        .convention("io.github.juuxel:unprotect:1.3.0")

    /** `mcpcleanup` — MCPConfig `forge:mcpCleanup` splice-in for
     *  notch-obf userdev sources chains. */
    val mcpcleanup: Property<String> = objects.property(String::class.java)
        .convention("net.minecraftforge:mcpcleanup:2.3.6")

    /** `DiffPatch` — MCPConfig `mcpconfig:diffpatch` tool used by
     *  userdev sources and binpatch subpipelines. */
    val diffpatch: Property<String> = objects.property(String::class.java)
        .convention("net.minecraftforge:DiffPatch:2.0.12")

    /** jvmdowngrader java-api — runtime dep the `jvmdg` adapter adds
     *  to downgraded environments' classpaths. */
    val jvmdowngraderJavaApi: Property<String> =
        objects.property(String::class.java)
            .convention(
                "xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:2.0.1"
            )

    /** Forgix engine — resolved into `loaderMergeForgix` and invoked
     *  reflectively by `mergeLoaderJars`, so bumping the version here
     *  swaps the merge implementation without a unimined release
     *  (entry point: `Forgix.mergeLoaders(Map, File)`). */
    val forgix: Property<String> = objects.property(String::class.java)
        .convention("io.github.pacifistmc.forgix:Forgix:2.0.0")

    /** ClasspathPluginLoader agent jar (with `:all` classifier) —
     *  bukkit environments' plugin classpath loader. */
    val classpathPluginLoader: Property<String> =
        objects.property(String::class.java)
            .convention(
                "xyz.wagyourtail.unimined:classpath-plugin-loader:" +
                        "0.1.0-SNAPSHOT:all"
            )

    /** `stitch` — yarn-v1 `proposeFieldNames` tool invoked during
     *  mappings materialization. */
    val stitch: Property<String> = objects.property(String::class.java)
        .convention("net.fabricmc:stitch:0.6.2")

    private val knownTools: Map<String, Property<String>> by lazy {
        mapOf(
            "net.neoforged.jst:jst-cli" to jst,
            "io.github.juuxel:unprotect" to unprotect,
            "net.minecraftforge:mcpcleanup" to mcpcleanup,
            "net.minecraftforge:DiffPatch" to diffpatch,
            "xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api"
                    to jvmdowngraderJavaApi,
            "io.github.pacifistmc.forgix:Forgix" to forgix,
            "xyz.wagyourtail.unimined:classpath-plugin-loader"
                    to classpathPluginLoader,
            "net.fabricmc:stitch" to stitch,
        )
    }

    /** Resolve a tool coordinate through this registry: when [coord]'s
     *  `group:artifact` matches a known tool's default, the tool's
     *  (possibly user-overridden) notation is substituted; otherwise
     *  [coord] passes through untouched — profile-declared tool
     *  versions stay profile-driven. */
    fun remapToolCoord(coord: String): String {
        val base = coord.removeSuffix("@jar").removeSuffix("@zip")
            .removeSuffix("@pom")
        val parts = base.split(":")
        if (parts.size < 2) return coord
        val key = "${parts[0]}:${parts[1]}"
        return knownTools[key]?.get() ?: coord
    }
}

/** Get or register the project's `uniminedTools` extension — safe to
 *  call from any unimined plugin at any apply order. */
fun Project.uniminedTools(): UniminedTools =
    extensions.findByType(UniminedTools::class.java)
        ?: extensions.create("uniminedTools", UniminedTools::class.java)
