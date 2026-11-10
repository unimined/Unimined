package xyz.wagyourtail.unimined.transform.jarmod

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import xyz.wagyourtail.commonskt.utils.toHex
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.transform.BundledResource
import xyz.wagyourtail.unimined.transform.BundledResourceChannel
import xyz.wagyourtail.unimined.transform.TransformEnvironmentSpec
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.transform.jarmod.internal.fg1.FMLChecksumPatchProcessor
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * FG1-era FML dynamic-library provisioning — the port of 1.x
 * `FG1MinecraftTransformer.dynLibs`/`resolveDynLibs`, shared between
 * the jarmod-family path (`forge` configuration) and the forge-family
 * path (`loader("forge")` on an FG1-era zip-patches artifact, whose
 * probed merge jars play the same role).
 *
 * `cpw/mods/fml/relauncher/CoreFMLLibraries.<clinit>` inside the era
 * forge zip carries parallel `libraries[]`/`checksums[]` String
 * arrays — the lib jars FML downloads into `runDir/lib` at runtime.
 * Per env this wires:
 *  - a `forgeDeps<env>` configuration (extended into the env's
 *    `minecraftLibraries`) holding the maven-resolvable equivalents —
 *    the checksum patch rewrites `checksums[]` to whatever we ship, so
 *    resolved jars needn't be FML's exact hosted copies;
 *  - bundled `/fmllibs/` resources (the unpublishable
 *    `deobfuscation_data_1.5.2.zip`) extracted under `build/` and
 *    added as file deps;
 *  - an [FMLChecksumPatchProcessor] env jarProcessor baking the
 *    shipped jars' SHA1s into the transformed artifact's
 *    `CoreFMLLibraries` (plus `unimined/fg1-lib-sums.txt` for the
 *    spliced runtime helper);
 *  - run mutators staging `forgeDeps` into `runDir/lib` under FML's
 *    expected names and emitting `-Dunimined.fg1.libSums` for
 *    classpath-carried (tx-spliced) `CoreFMLLibraries` copies;
 *  - a `libraryFilters` entry dropping `org.ow2.asm` — FML supplies
 *    its own asm-all, so MC-declared copies must not reach the
 *    classpath.
 */
object FG1DynLibWiring {

    /** FML's dyn-lib rename: the resolved bcprov jar names gain a dot
     *  that `CoreFMLLibraries` never asks for. */
    private val depNameMap = mapOf(
        "bcprov-jdk15on-1.47.jar" to "bcprov-jdk15on-147.jar",
        "bcprov-jdk15on-1.48.jar" to "bcprov-jdk15on-148.jar",
    )

    /**
     * Provision FG1 dyn-libs for [environment].
     *
     * @param forgeZips the era forge zips to scan for
     *   `CoreFMLLibraries` — lazily resolved inside `afterDslEval` so
     *   provider evaluation stays out of DSL evaluation
     * @param compileClasspathJars extra dependencies (e.g. jarmod's
     *   `forge` configuration — the era zip ships FML classes mods
     *   compile against) extended into the env's **compile**
     *   classpath only — never runtime: the zip's obf
     *   `net.minecraft.*` classes would shadow the remapped merged
     *   jar and break linkage. Empty when the transformed jar
     *   already carries the loader content (forge-family merged
     *   zip-patches)
     * @param useAsm52 substitute `org.ow2.asm:asm-all:5.2` for the
     *   FML-pinned asm-all-4.x (jarmod's `useAsm52` knob; no
     *   forge-family equivalent yet)
     * @param checksumPatchSink where the [FMLChecksumPatchProcessor]
     *   registers — `envSpec.jarProcessors` for jarmod (its merge
     *   lands earlier in the same list); `envSpec.postPipelineJarProcessors`
     *   when the merge rides the declared pipeline (forge-family
     *   `jarmod:merge` step), so the patch sees the merged
     *   `CoreFMLLibraries`
     */
    fun wire(
        project: Project,
        environment: MinecraftEnvironment,
        envSpec: TransformEnvironmentSpec,
        forgeZips: Provider<Set<File>>,
        compileClasspathJars: List<org.gradle.api.artifacts.Configuration> = emptyList(),
        useAsm52: Boolean = false,
        checksumPatchSink: (xyz.wagyourtail.unimined.transform.processor.JarProcessor) -> Unit =
            { envSpec.jarProcessors += it },
    ) {
        val envId = environment.environmentId
        val forgeDeps = project.configurations.maybeCreate(
            if (envId == "main") "forgeDeps" else
                "forgeDeps${envId.replaceFirstChar { it.uppercaseChar() }}"
        ).apply { isCanBeConsumed = false }
        project.configurations.matching {
            it.name == environment.minecraftLibrariesConfigurationName
        }.all {
            it.extendsFrom(forgeDeps)
        }
        // loader jars are compile-classpath-only — the era zip's obf
        // `net.minecraft.*` classes must not shadow the remapped
        // product jar on the runtime classpath
        if (compileClasspathJars.isNotEmpty()) {
            val compileName = project.extensions
                .getByType(org.gradle.api.plugins.JavaPluginExtension::class.java)
                .sourceSets.findByName(envId)?.compileClasspathConfigurationName
            if (compileName != null) {
                project.configurations.matching { it.name == compileName }.all {
                    it.extendsFrom(*compileClasspathJars.toTypedArray())
                }
            }
        }

        // maven-resolvable fmllibs — the checksum patch rewrites
        // CoreFMLLibraries.checksums to whatever we ship, so the
        // resolved jars needn't be FML's exact hosted copies
        val mavenDeps = mapOf(
            "argo-2.25.jar" to "net.sourceforge.argo:argo:2.25",
            "guava-12.0.1.jar" to "com.google.guava:guava:12.0.1",
            "guava-14.0-rc3.jar" to "com.google.guava:guava:14.0-rc3",
            "asm-all-4.0.jar" to "org.ow2.asm:asm-all:4.0",
            "asm-all-4.1.jar" to "org.ow2.asm:asm-all:4.1",
            "scala-library.jar" to "org.scala-lang:scala-library:2.9.1-1",
            "bcprov-jdk15on-148.jar" to "org.bouncycastle:bcprov-jdk15on:1.48",
            "bcprov-jdk15on-147.jar" to "org.bouncycastle:bcprov-jdk15on:1.47",
            // argo-small is FML's slim build of argo 3.2 — never
            // published; the full jar is a superset and the checksum
            // patch covers the hash
            "argo-small-3.2.jar" to "net.sourceforge.argo:argo:3.2",
        )
        // 1.5.2's deobfuscation_data zip is FML-*generated* content —
        // no maven home; and its joined.srg is NOT the src bundle's
        // conf/joined.srg (1586245 vs 1425098 bytes), so it can't be
        // regenerated. Stays bundled.
        val bundled = setOf("deobfuscation_data_1.5.2.zip")

        val wanted = forgeZips.map { zips ->
            val out = mutableSetOf<String>()
            for (zip in zips.filter { it.isFile }) {
                ZipFile(zip).use { z ->
                    z.getEntry("cpw/mods/fml/relauncher/CoreFMLLibraries.class")
                        ?.let { e -> z.getInputStream(e).use { out += dynLibs(it) } }
                }
            }
            out.toSet()
        }

        // `wanted.get()` resolves the forge zips — defer to
        // afterDslEval so a user-declared `forge(...)` dep (the 1.x
        // `forge { loader }` model) lands in `dependencies {}` first;
        // otherwise the config is observed at bind time and
        // `dependencies { forge(...) }` fails "cannot mutate after
        // resolved"
        project.afterDslEval {
            val names = wanted.get()
            val wantedAsm = names.firstOrNull { it.startsWith("asm-all-") }
            if (wantedAsm != null) {
                // 1.x libraryFilter — FML supplies its own asm-all, so
                // the MC-declared ow2 libs must not reach the classpath
                environment.libraryFilters += { it.group != "org.ow2.asm" }
            }
            // fmllibs jars whose FML-pinned sha1 no maven coord
            // matches (FML's own repackaged asm-all-4.0, etc.) —
            // fetched as exact copies from the wag flat fmllibs
            // store so the checksum passes unpatched
            val wagFmlLibs = setOf("asm-all-4.0.jar")
            for ((name, coord) in mavenDeps) {
                if (name !in names) continue
                when {
                    useAsm52 && name.startsWith("asm-all-") ->
                        project.dependencies.add(forgeDeps.name, "org.ow2.asm:asm-all:5.2")
                    name in wagFmlLibs ->
                        project.dependencies.add(forgeDeps.name,
                            project.files(project.provider {
                                resolveFmlLib(project, name).toFile() }))
                    else ->
                        project.dependencies.add(forgeDeps.name, coord)
                }
            }
            for (name in bundled) {
                if (name !in names) continue
                if (useAsm52 && name.startsWith("asm-all-")) {
                    project.dependencies.add(forgeDeps.name, "org.ow2.asm:asm-all:5.2")
                    continue
                }
                val resource = BundledResource("/fmllibs/$name")
                envSpec.bundledResources += resource
                val target = BundledResourceChannel.materialize(
                    project, envId, resource, FG1DynLibWiring::class.java)
                project.dependencies.add(forgeDeps.name, project.files(target))
            }

            // resolved jars land in lib/ under FML's expected names —
            // asm-5.2 is copied under the wanted asm-all-4.x name
            val renames = depNameMap +
                (if (useAsm52 && wantedAsm != null)
                    mapOf("asm-all-5.2.jar" to wantedAsm) else emptyMap()) +
                (if ("argo-small-3.2.jar" in names)
                    mapOf("argo-3.2.jar" to "argo-small-3.2.jar") else emptyMap())

            // patch CoreFMLLibraries.checksums to the SHA1s of the jars
            // we actually ship (lazily — resolves forgeDeps at spec
            // build); sink picks the ordering channel — must see the
            // merged jar's class
            checksumPatchSink(FMLChecksumPatchProcessor {
                forgeDeps.resolve()
                    .associate { (renames[it.name] ?: it.name) to it }
                    .filterKeys { it in names }
            })

            val transform = project.extensions.getByType(TransformExtension::class.java)
            transform.sourceSet(envId).runMutators += org.gradle.api.Action { config ->
                config.jvmArgs += "-Dminecraft.applet.TargetDirectory=${'$'}{game_dir}"
                // per-run lib sums — CoreFMLLibraries can also load
                // from a (tx-spliced) classpath forge zip; the spliced
                // helper reads this property and rewrites `checksums[]`
                // to match what we stage. The merged-jar copy reads
                // the baked `unimined/fg1-lib-sums.txt` instead —
                // emitting the prop either way is harmless
                val sums = forgeDeps.resolve()
                    .associate { (renames[it.name] ?: it.name) to it }
                    .filterKeys { it in names }
                if (sums.isNotEmpty()) {
                    val prop = sums.entries.joinToString(";") { (n, f) ->
                        "$n=${sha1Hex(f)}"
                    }
                    config.jvmArgs += "-Dunimined.fg1.libSums=$prop"
                }
            }
            // prepareTaskMutators stage the run dir — forgeDeps into
            // runDirectory/lib (FML's dyn-lib dir) via PrepareRunTask's
            // dynLib channel; a doFirst closure would serialize the
            // captured Configuration into the config cache
            transform.sourceSet(envId).prepareTaskMutators += org.gradle.api.Action { task ->
                @Suppress("UNCHECKED_CAST")
                (task.property("dynLibFiles") as?
                        org.gradle.api.file.ConfigurableFileCollection)
                    ?.from(project.provider {
                        forgeDeps.resolve()
                            .filter { it.isFile && it.extension != "pom" }
                    })
                @Suppress("UNCHECKED_CAST")
                (task.property("dynLibInto") as?
                        org.gradle.api.provider.Property<String>)?.set("lib")
                @Suppress("UNCHECKED_CAST")
                (task.property("dynLibRenames") as?
                        org.gradle.api.provider.MapProperty<String, String>)
                    ?.putAll(renames)
            }
        }
    }

    /** Flat-file fetch for the exact FML-pinned fmllibs jars that
     *  maven can't supply a checksum-matching copy of (FML's own
     *  repackaged asm-all-4.0, etc.) — hosted as a flat store at
     *  `releases/fmllibs/<name>` on the wag maven (1.x shipped these
     *  as bundled `/fmllibs/` resources). Honors --offline/refresh. */
    private fun resolveFmlLib(
        project: Project,
        name: String
    ): java.nio.file.Path {
        val dest = UniminedCache.global(project)
            .resolve("fmllibs").resolve(name)
        val refresh = project.gradle.startParameter.isRefreshDependencies
        if (java.nio.file.Files.exists(dest) && !refresh) return dest
        if (project.gradle.startParameter.isOffline) {
            if (java.nio.file.Files.exists(dest)) return dest
            throw org.gradle.api.GradleException(
                "offline and no cached fmllib $name under $dest")
        }
        java.nio.file.Files.createDirectories(dest.parent)
        try {
            java.net.URI.create(
                "https://maven.wagyourtail.xyz/releases/fmllibs/$name")
                .toURL().openStream().use {
                java.nio.file.Files.copy(it, dest,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            throw org.gradle.api.GradleException(
                "could not fetch fmllib $name from the wag fmllibs store", e)
        }
        return dest
    }

    private fun sha1Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        f.inputStream().use { s ->
            val buf = ByteArray(8192)
            while (true) {
                val n = s.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    /** Read the dyn-lib names out of `CoreFMLLibraries.<clinit>` —
     *  the String-array LDCs stored into `libraries`
     *  (1.x `FG1MinecraftTransformer.getDynLibs`). */
    private fun dynLibs(stream: java.io.InputStream): Set<String> {
        val cn = org.objectweb.asm.tree.ClassNode().also {
            org.objectweb.asm.ClassReader(stream).accept(it, 0)
        }
        val clInit = cn.methods.firstOrNull { it.name == "<clinit>" } ?: return emptySet()
        val out = mutableSetOf<String>()
        var inArray = false
        for (insn in clInit.instructions) {
            if (insn is org.objectweb.asm.tree.TypeInsnNode &&
                insn.opcode == org.objectweb.asm.Opcodes.ANEWARRAY &&
                insn.desc == "java/lang/String") inArray = true
            if (inArray && insn is org.objectweb.asm.tree.LdcInsnNode && insn.cst is String)
                out.add(insn.cst as String)
            if (insn is org.objectweb.asm.tree.FieldInsnNode &&
                insn.opcode == org.objectweb.asm.Opcodes.PUTSTATIC) {
                if (insn.name == "libraries") break
                inArray = false; out.clear()
            }
        }
        return out
    }

}
