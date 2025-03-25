package xyz.wagyourtail.unimined.internal.minecraft.patch.fabric

import com.google.gson.*
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.minecraft.patch.ataw.AccessWidenerPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.fabric.FabricLikePatcher
import xyz.wagyourtail.unimined.api.minecraft.task.RemapJarTask
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.mapping.task.ExportMappingsTask
import xyz.wagyourtail.unimined.api.minecraft.task.AbstractRemapJarTask
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.api.uniminedMaybe
import xyz.wagyourtail.unimined.internal.mapping.ii.InterfaceInjectionMinecraftTransformer
import xyz.wagyourtail.unimined.internal.mapping.task.ExportMappingsTaskImpl
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.AbstractMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.access.widener.AccessWidenerMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.resolver.Library
import xyz.wagyourtail.unimined.internal.minecraft.resolver.parseLibrary
import xyz.wagyourtail.unimined.internal.minecraft.transform.merge.ClassMerger
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.tiny.v2.TinyV2Writer
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.util.*
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.*

abstract class FabricLikeMinecraftTransformer(
    project: Project,
    provider: MinecraftProvider,
    providerName: String,
    val modJsonName: String,
    val accessWidenerJsonKey: String,
    private val accessWidenerTransformer: AccessWidenerMinecraftTransformer = AccessWidenerMinecraftTransformer(project, provider, providerName),
): AbstractMinecraftTransformer(
    project,
    provider,
    providerName
), FabricLikePatcher, AccessWidenerPatcher by accessWidenerTransformer {

    companion object {
        val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
    }

    val fabric: Configuration = project.configurations.maybeCreate(providerName.withSourceSet(provider.sourceSet)).also {
        provider.minecraftLibraries.extendsFrom(it)
    }

    private val fabricJson: Configuration = project.configurations.detachedConfiguration()

    private val include: Configuration = project.configurations.maybeCreate("include".withSourceSet(provider.sourceSet))

    override var customIntermediaries: Boolean by FinalizeOnRead(!provider.obfuscated)

    override var skipInsertAw: Boolean by FinalizeOnRead(false)

    protected abstract val ENVIRONMENT: String
    protected abstract val ENV_TYPE: String

    override val merger: ClassMerger = ClassMerger(
        { node, env ->
            if (env == EnvType.JOINED) return@ClassMerger
            if (isAnonClass(node)) return@ClassMerger
            val visitor = node.visitAnnotation(ENVIRONMENT, true)
            visitor.visitEnum("value", ENV_TYPE, env.name)
            visitor.visitEnd()
        },
        { node, env ->
            if (env != EnvType.JOINED) {
                val visitor = node.visitAnnotation(ENVIRONMENT, true)
                visitor.visitEnum("value", ENV_TYPE, env.name)
                visitor.visitEnd()
            }
        },
        { node, env ->
            if (env != EnvType.JOINED) {
                val visitor = node.visitAnnotation(ENVIRONMENT, true)
                visitor.visitEnum("value", ENV_TYPE, env.name)
                visitor.visitEnd()
            }
        }
    )

    abstract val defaultProdNamespace: String

    override var prodNamespace: Namespace
        get() = super.prodNamespace
        set(value) {
            super.prodNamespace = value
        }

    override fun defaultProdNamespace(): Namespace {
        if (!provider.obfuscated) return provider.mappings.checkedNs("official")
        if (customIntermediaries) throw IllegalStateException("Custom intermediaries are enabled, but prodNamespace is not overriden")
        return provider.mappings.checkedNs(defaultProdNamespace)
    }

    override fun prodNamespace(namespace: String) {
        super.prodNamespace(namespace)
        accessWidenerTransformer.prodNamespace(namespace)
    }

    @get:ApiStatus.Internal
    @set:ApiStatus.Experimental
    override var devMappings: Path? by FinalizeOnRead(LazyMutable {
        if (!provider.obfuscated) return@LazyMutable null
        runBlocking {
            provider.localCache
                .resolve("mappings")
                .createDirectories()
                .resolve("intermediary2named-${provider.mappings.combinedNames()}.jar")
                .apply {
                    val file = resolveSibling("mappings.tiny").toFile()
                    val export = ExportMappingsTaskImpl.ExportImpl(provider.mappings).apply {
                        location = file
                        type = TinyV2Writer
                        sourceNamespace = prodNamespace
                        targetNamespace = setOf(provider.mappings.devNamespace)
                        renameNs[prodNamespace] = "intermediary"
                        renameNs[provider.mappings.devNamespace] = "named"
                    }
                    export.validate()
                    runBlocking {
                        export.exportFunc(provider.mappings.resolve())
                    }
                    ZipArchiveOutputStream(outputStream()).use {
                        it.putArchiveEntry(ZipArchiveEntry("mappings/mappings.tiny"))
                        it.write(file.readBytes())
                        it.closeArchiveEntry()
                    }
                }
        }
    })

    init {
        addMavens()
    }

    @Deprecated("", replaceWith = ReplaceWith("prodNamespace(namespace)"))
    override fun setProdNamespace(namespace: String) {
        prodNamespace(namespace)
    }

    protected abstract fun addMavens()
    protected abstract fun addIntermediaryMappings()

    var mainClass: JsonObject? = null

    override fun beforeMappingsResolve() {
        if (!customIntermediaries) {
            addIntermediaryMappings()
        }
    }

    val fabricDep by lazy {
        val dependencies = fabric.dependencies

        if (dependencies.isEmpty()) {
            throw IllegalStateException("No dependencies found for fabric provider")
        }

        if (dependencies.size > 1) {
            throw IllegalStateException("Multiple dependencies found for fabric provider")
        }

        dependencies.first()
    }

    override fun apply() {
        val client = provider.side == EnvType.CLIENT || provider.side == EnvType.JOINED
        val server = provider.side == EnvType.SERVER || provider.side == EnvType.JOINED

        var artifactString = ""
        if (fabricDep.group != null) {
            artifactString += fabricDep.group + ":"
        }
        artifactString += fabricDep.name
        if (fabricDep.version != null) {
            artifactString += ":" + fabricDep.version
        }
        artifactString += "@json"

        if (fabricJson.dependencies.isEmpty()) {
            fabricJson.dependencies.add(
                project.dependencies.create(
                    artifactString
                )
            )
        }

        val json = InputStreamReader(
            fabricJson.getFiles(fabricJson.dependencies.last(), "json")
                .last()
                .inputStream()
        ).use { reader ->
            JsonParser.parseReader(reader).asJsonObject
        }

        val libraries = json.get("libraries")?.asJsonObject
        if (libraries != null) {
            libraries.get("common")?.asJsonArray?.forEach {
                createFabricLoaderDependency(it)
            }
            if (client) {
                libraries.get("client")?.asJsonArray?.forEach {
                    createFabricLoaderDependency(it)
                }
            }
            if (server) {
                libraries.get("server")?.asJsonArray?.forEach {
                    createFabricLoaderDependency(it)
                }
            }
            libraries.get("development")?.asJsonArray?.forEach {
                createFabricLoaderDependency(it)
            }
        }

        mainClass = json.get("mainClass")?.asJsonObject

        if (devMappings != null) {
            provider.sourceSet.runtimeClasspath += project.files(devMappings!!)
        }

        // mixins get remapped at runtime, so we don't need to on fabric
        provider.mods.default {
            mixinRemap {
                off()
            }
        }

        super.apply()
    }

    private fun createFabricLoaderDependency(it: JsonElement) {
        val lib = parseLibrary(it.asJsonObject)
        if (lib.rules.all { it.testRule() }) {
            val dep: ModuleDependency = project.dependencies.create(
                lib.name
            ) as ModuleDependency
            dep.isTransitive = false
            provider.minecraftLibraries.dependencies.add(dep)
        }
    }

    override fun afterRemap(baseMinecraft: MinecraftJar): MinecraftJar = applyInterfaceInjection(accessWidenerTransformer.afterRemap(baseMinecraft))

    private fun applyInterfaceInjection(baseMinecraft: MinecraftJar): MinecraftJar {
        val injections = hashMapOf<String, List<String>>()

        this.collectInterfaceInjections(baseMinecraft, injections)

        return if (injections.isNotEmpty()) {
            val oldSuffix = if (baseMinecraft.awOrAt != null) baseMinecraft.awOrAt + "+" else ""

            val output = MinecraftJar(
                baseMinecraft,
                parentPath = provider.localCache.resolve("fabric").createDirectories(),
                awOrAt = "${oldSuffix}ii+${injections.getShortSha1()}"
            )

            if (!output.path.exists() || project.unimined.forceReload) {
                if (InterfaceInjectionMinecraftTransformer.transform(
                        injections,
                        baseMinecraft.path,
                        output.path,
                        project.logger
                    )
                ) {
                    output
                } else baseMinecraft
            } else output
        } else baseMinecraft
    }

    abstract fun collectInterfaceInjections(baseMinecraft: MinecraftJar, injections: HashMap<String, List<String>>)
    fun collectInterfaceInjections(baseMinecraft: MinecraftJar, injections: HashMap<String, List<String>>, interfaces: JsonObject) = runBlocking {
        injections.putAll(interfaces.entrySet()
            .filterNotNull()
            .filter { it.key != null && it.value != null && it.value.isJsonArray }
            .map {
                val element = it.value!!

                Pair(it.key!!, if (element.isJsonArray) {
                    element.asJsonArray.mapNotNull { name -> name.asString }
                } else arrayListOf())
            }
            .map {
                var target = it.first

                val clazz = provider.mappings.resolve().getClass(
                    prodNamespace,
                    InternalName.read(target),
                )

                if (clazz != null) {
                    var newTarget = clazz.getName(baseMinecraft.mappingNamespace)

                    if (newTarget != null) {
                        target = newTarget.value
                    }
                }

                Pair(target, it.second)
            }
        )
    }

    val intermediaryClasspath: Path by lazy {
        val icp = provider.localCache.resolve("remapClasspath.txt".withSourceSet(provider.sourceSet))
        project.logger.lifecycle("[Unimined/Fabric] Generating intermediary classpath.")
        // resolve intermediary classpath
        val classpath = (provider.mods.getClasspathAs(
            prodNamespace,
            provider.sourceSet.runtimeClasspath.filter { !provider.isMinecraftJar(it.toPath()) }.toSet()
        ) + provider.getMinecraft(prodNamespace).toFile()).filter { it.exists() && !it.isDirectory && (it.extension == "jar" || it.extension == "zip") }
        // write to file
        icp.writeText(classpath.joinToString(File.pathSeparator), options = arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
        icp
    }

    override fun afterRemapJarTask(remapJarTask: AbstractRemapJarTask, output: Path) {
        insertIncludes(output)
        insertAW(output)
    }

    private fun insertIncludes(output: Path) {
        if (include.dependencies.isEmpty()) {
            return
        }
        output.openZipFileSystem(mapOf("mutable" to true)).use { fs ->
            val includeCache = provider.localCache.resolve("includeCache".withSourceSet(provider.sourceSet))
            val jars = fs.getPath("META-INF/jars")

            val mod = fs.getPath(modJsonName)
            if (!Files.exists(mod)) {
                throw IllegalStateException("$modJsonName not found in jar")
            }
            val json = JsonParser.parseReader(InputStreamReader(Files.newInputStream(mod))).asJsonObject

            Files.createDirectories(jars)
            Files.createDirectories(includeCache)
            var errored = false
            for (dep in include.dependencies) {
                val files = include.getFiles(dep, "jar")
                if (files.isEmpty) continue
                try {
                    val source = files.singleFile.toPath()
                    val path = jars.resolve("${dep.name}-${dep.version}.jar")
                    if (!source.zipContains(modJsonName)) {
                        val cachePath = includeCache.resolve("${dep.name}-${dep.version}.jar")
                        if (!cachePath.exists() || project.unimined.forceReload || project.gradle.startParameter.isRefreshDependencies) {
                            try {
                                ZipArchiveOutputStream(
                                    cachePath.outputStream(
                                        StandardOpenOption.CREATE,
                                        StandardOpenOption.TRUNCATE_EXISTING
                                    )
                                ).use { out ->
                                    source.forEntryInZip { entry, stream ->
                                        out.putArchiveEntry(entry)
                                        stream.copyTo(out)
                                        out.closeArchiveEntry()
                                    }
                                    out.putArchiveEntry(ZipArchiveEntry(modJsonName).also { entry ->
                                        entry.time = CONSTANT_TIME_FOR_ZIP_ENTRIES
                                    })
                                    val innerjson = JsonObject()
                                    innerjson.addProperty("schemaVersion", 1)
                                    var artifactString = ""
                                    if (dep.group != null) {
                                        artifactString += dep.group!!.replace(".", "_") + "_"
                                    }
                                    artifactString += dep.name
                                    innerjson.addProperty("id", artifactString.lowercase())
                                    innerjson.addProperty("version", dep.version)
                                    innerjson.addProperty("name", dep.name)
                                    val custom = JsonObject()
                                    custom.addProperty("fabric-loom:generated", true)
                                    custom.addProperty("unimined:generated", true)
                                    innerjson.add("custom", custom)
                                    out.write(GSON.toJson(innerjson).toByteArray())
                                    out.closeArchiveEntry()
                                }
                            } catch (e: Exception) {
                                project.logger.error(
                                    "[Unimined/Fabric] Failed to create $modJsonName stub for ${source.absolutePathString()}.",
                                    e
                                )
                                throw e
                            }
                        }
                        cachePath.copyTo(path, StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        source.copyTo(path, StandardCopyOption.REPLACE_EXISTING)
                    }

                    addIncludeToModJson(json, dep, path.toString().removePrefix("/"))
                } catch (e: Exception) {
                    project.logger.error("Failed on $dep", e)
                    errored = true
                }
            }
            if (errored) {
                throw IllegalStateException("An error occured resolving includes")
            }
            Files.write(mod, GSON.toJson(json).toByteArray(), StandardOpenOption.TRUNCATE_EXISTING)
        }
    }

    protected abstract fun addIncludeToModJson(json: JsonObject, dep: Dependency, path: String)

    private fun insertAW(output: Path) {
        if (accessWidener != null && !skipInsertAw) {
            output.openZipFileSystem(mapOf("mutable" to true)).use { fs ->
                val mod = fs.getPath(modJsonName)
                if (!Files.exists(mod)) {
                    throw IllegalStateException("$modJsonName not found in jar")
                }
                val aw = accessWidener!!.toPath()
                var parent = aw.parent
                while (!fs.getPath(parent.relativize(aw).toString()).exists()) {
                    parent = parent.parent
                    if (parent.relativize(aw).toString() == aw.toString()) {
                        throw IllegalStateException("Access widener not found in jar")
                    }
                }
                val awPath = fs.getPath(parent.relativize(aw).toString())
                val json = JsonParser.parseReader(InputStreamReader(Files.newInputStream(mod))).asJsonObject
                json.addProperty(accessWidenerJsonKey, awPath.toString())
                Files.write(mod, GSON.toJson(json).toByteArray(), StandardOpenOption.TRUNCATE_EXISTING)
            }
        }
    }

    val groups: String by lazy {
        val groups = sortProjectSourceSets().mapValues { it.value.toMutableSet() }.toMutableMap()
        groups.putIfAbsent(this.project to this.provider.sourceSet, mutableSetOf())

        // detect non-fabric groups
        for ((proj, sourceSet) in groups.keys.toSet()) {
            if (proj.uniminedMaybe?.minecrafts?.get(sourceSet)?.mcPatcher !is FabricLikePatcher) {
                // merge with current
                proj.logger.warn("[Unimined/FabricLike] Non-fabric ${(proj to sourceSet).toPath()} found in fabric classpath groups, merging with current (${(project to provider.sourceSet).toPath()}), this should've been manually specified with `combineWith`")
                groups[this.project to this.provider.sourceSet]!! += groups[proj to sourceSet]!!
                groups.remove(proj to sourceSet)
            }
        }
        project.logger.info("[Unimined/FabricLike] Classpath groups: ${groups.map { it.key.toPath() + " -> " + it.value.joinToString(", ") { it.toPath() } }.joinToString("\n    ")}")
        groups.map { entry -> entry.value.flatMap { it.second.output }.joinToString(File.pathSeparator) { it.absolutePath } }.joinToString(File.pathSeparator.repeat(2))
    }

    override fun applyClientRunTransform(config: RunConfig) {
        mainClass?.get("client")?.asString?.let {
            config.mainClass.set(it)
        }
    }

    override fun applyServerRunTransform(config: RunConfig) {
        mainClass?.get("server")?.asString?.let {
            config.mainClass.set(it)
        }
    }

    override fun libraryFilter(library: Library): Library? {
        // fabric provides its own asm, exclude asm-all from vanilla minecraftLibraries
        if (library.name.startsWith("org.ow2.asm:asm-all")) {
            return null
        }
        return super.libraryFilter(library)
    }

    fun getModJsonPath(): File? {
        val json = provider.sourceSet.resources.firstOrNull { it.name.equals(modJsonName) }
        if (json == null) {
            project.logger.warn("[Unimined/FabricLike] $modJsonName not found in sourceSet ${provider.project.path} ${provider.sourceSet.name}")
            return null
        }
        return json
    }
}