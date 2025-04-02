package xyz.wagyourtail.unimined.internal.minecraft

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.intellij.lang.annotations.Language
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.mapping.MappingNamespaceTree
import xyz.wagyourtail.unimined.api.mapping.MappingsConfig
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.minecraft.patch.*
import xyz.wagyourtail.unimined.api.minecraft.patch.ataw.AccessConvert
import xyz.wagyourtail.unimined.api.minecraft.patch.ataw.AccessTransformerPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.ataw.AccessWidenerPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.bukkit.CraftbukkitPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.bukkit.SpigotPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.fabric.FabricLikePatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.fabric.LegacyFabricPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.CleanroomPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.ForgeLikePatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.MinecraftForgePatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.NeoForgedPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.jarmod.JarModAgentPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.rift.RiftPatcher
import xyz.wagyourtail.unimined.api.minecraft.task.RemapJarTask
import xyz.wagyourtail.unimined.api.minecraft.task.RemapSourcesJarTask
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.api.uniminedMaybe
import xyz.wagyourtail.unimined.internal.mapping.MappingsProvider
import xyz.wagyourtail.unimined.internal.mapping.task.ExportMappingsTaskImpl
import xyz.wagyourtail.unimined.internal.minecraft.patch.AbstractMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.NoTransformMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.access.AccessConvertImpl
import xyz.wagyourtail.unimined.internal.minecraft.patch.access.transformer.AccessTransformerMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.access.widener.AccessWidenerMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.bukkit.CraftbukkitMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.bukkit.SpigotMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.fabric.*
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.CleanroomMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.MinecraftForgeMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.NeoForgedMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod.JarModAgentMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.merged.MergedMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.rift.RiftMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.resolver.AssetsDownloader
import xyz.wagyourtail.unimined.internal.minecraft.resolver.Extract
import xyz.wagyourtail.unimined.internal.minecraft.resolver.Library
import xyz.wagyourtail.unimined.internal.minecraft.resolver.MinecraftDownloader
import xyz.wagyourtail.unimined.internal.minecraft.task.GenSourcesTaskImpl
import xyz.wagyourtail.unimined.internal.minecraft.task.RemapJarTaskImpl
import xyz.wagyourtail.unimined.internal.minecraft.task.RemapSourcesJarTaskImpl
import xyz.wagyourtail.unimined.internal.mods.ModsProvider
import xyz.wagyourtail.unimined.internal.runs.RunsProvider
import xyz.wagyourtail.unimined.internal.source.SourceProvider
import xyz.wagyourtail.unimined.util.*
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.io.path.*

open class MinecraftProvider(project: Project, sourceSet: SourceSet) : MinecraftConfig(project, sourceSet) {
    override val minecraftData = MinecraftDownloader(project, this)

    override val obfuscated = true

    override val mappings = MappingsProvider(project, this)

    override var mcPatcher: MinecraftPatcher by FinalizeOnRead(FinalizeOnWrite(NoTransformMinecraftTransformer(project, this)))

    override val mods = ModsProvider(project, this)

    override val runs = RunsProvider(project, this)

    override val minecraftRemapper = MinecraftRemapper(project, this)

    override val sourceProvider = SourceProvider(project, this)

    protected val patcherActions = ArrayDeque<() -> Unit>()
    private var lateActionsRunning by FinalizeOnWrite(false)

    override val combinedWithList = mutableSetOf<Pair<Project, SourceSet>>()

    val libraryReplaceMap = mutableListOf<(String) -> Pair<Boolean, String?>>()

    var applied: Boolean by FinalizeOnWrite(false)
        private set

    override val minecraft: Configuration = project.configurations.maybeCreate("minecraft".withSourceSet(sourceSet)).also {
        sourceSet.compileClasspath += it
        sourceSet.runtimeClasspath += it
    }

    override val minecraftLibraries: Configuration = project.configurations.maybeCreate("minecraftLibraries".withSourceSet(sourceSet)).also {
        sourceSet.compileClasspath += it
        sourceSet.runtimeClasspath += it
        it.setTransitive(false)
    }

    init {
        replaceLibraryVersion("ca\\.weblite", "java-objc-bridge", "natives-osx") { null }
    }

    override fun from(project: Project, sourceSet: SourceSet) {
        val delegate = MinecraftProvider::class.getField("mcPatcher")!!.getDelegate(this) as FinalizeOnRead<FinalizeOnWrite<MinecraftPatcher>>
        if (delegate.finalized || (delegate.value as FinalizeOnWrite<MinecraftPatcher>).finalized) {
            throw IllegalStateException("mcPatcher is already finalized before from() call, from should really be called at the top...")
        }
        if (project != this.project) {
            this.project.evaluationDependsOn(project.path)
        }
        project.unimined.minecraftConfiguration[sourceSet]!!(this)
        if (delegate.finalized) {
            project.logger.warn("[Unimined/Minecraft ${project.path}:${sourceSet.name}] un-finalizing mcPatcher set in from() call")
        }
        delegate.finalized = false
        (delegate.value as FinalizeOnWrite<MinecraftPatcher>).finalized = false
    }

    override fun combineWith(project: Project, sourceSet: SourceSet) {
        project.logger.lifecycle("[Unimined/Minecraft ${project.path}:${this.sourceSet.name}] Combining with ${project.path}:${sourceSet.name}")
        if (combinedWithList.add(project to sourceSet)) {
            if (project.uniminedMaybe != null && project.unimined.minecrafts.contains(sourceSet)) {
                from(project, sourceSet)
            }
            this.sourceSet.compileClasspath += sourceSet.output
            this.sourceSet.runtimeClasspath += sourceSet.output
        }
        // remove unimined deps
    }

    override fun remap(task: Task, name: String, action: RemapJarTask.() -> Unit): TaskProvider<RemapJarTask> {
        val remapTask = project.tasks.register(name, RemapJarTaskImpl::class.java, this)
        remapTask.configure {
            it.dependsOn(task)
            if (task is Jar) {
                it.inputFile.set(task.archiveFile)
            }
            it.action()
            mcPatcher.configureRemapJar(it)
        }
        return remapTask as TaskProvider<RemapJarTask>
    }

    override fun remapSources(task: Task, name: String, action: RemapSourcesJarTask.() -> Unit): TaskProvider<RemapSourcesJarTask> {
        val remapTask = project.tasks.register(name, RemapSourcesJarTaskImpl::class.java, this)
        remapTask.configure {
            it.dependsOn(task)
            if (task is Jar) {
                it.inputFile.set(task.archiveFile)
            }
            it.action()
            mcPatcher.configureRemapJar(it)
        }
        return remapTask as TaskProvider<RemapSourcesJarTask>
    }

    override val mergedOfficialMinecraftFile: File? by lazy {
        val client = minecraftData.minecraftClient
        if (!client.path.exists()) throw IOException("minecraft path $client does not exist")
        val server = minecraftData.minecraftServer
        val noTransform = NoTransformMinecraftTransformer(project, this)
        if (noTransform.canCombine) {
            noTransform.merge(client, server).path.toFile()
        } else {
            null
        }
    }

    protected open val minecraftFiles: Map<Pair<MappingNamespaceTree.Namespace, MappingNamespaceTree.Namespace>, MinecraftJar> = defaultedMapOf {
        project.logger.info("[Unimined/Minecraft ${project.path}:${sourceSet.name}] Providing minecraft files for $it")
        val mc = if (side == EnvType.COMBINED) {
            val client = minecraftData.minecraftClient
            if (!client.path.exists()) throw IOException("minecraft path $client does not exist")
            val server = minecraftData.minecraftServer
            (mcPatcher as AbstractMinecraftTransformer).merge(client, server)
        } else {
            minecraftData.getMinecraft(side)
        }
        val path = (mcPatcher as AbstractMinecraftTransformer).afterRemap(
            minecraftRemapper.provide((mcPatcher as AbstractMinecraftTransformer).transform(mc), it.first, it.second)
        )
        if (!path.path.exists()) throw IOException("minecraft path $path does not exist")
        path
    }

    override fun getMinecraft(namespace: MappingNamespaceTree.Namespace, fallbackNamespace: MappingNamespaceTree.Namespace): Path {
        synchronized(this) {
            return minecraftFiles[namespace to fallbackNamespace]?.path
                ?: error("minecraft file not found for $namespace")
        }
    }

    private val mojmapIvys by lazy {
        if (minecraftData.hasMappings) {
            // add provider for client-mappings
            project.repositories.ivy { ivy ->
                ivy.name = "Official Client Mapping Provider"
                ivy.patternLayout {
                    it.artifact(minecraftData.officialClientMappingsFile.name)
                }
                ivy.url = minecraftData.officialClientMappingsFile.parentFile.toURI()
                ivy.metadataSources { sources ->
                    sources.artifact()
                }
                ivy.content {
                    it.includeVersion(mavenGroup, "client-mappings", version)
                }
            }

            // add provider for server-mappings
            project.repositories.ivy { ivy ->
                ivy.name = "Official Server Mapping Provider"
                ivy.patternLayout {
                    it.artifact(minecraftData.officialServerMappingsFile.name)
                }
                ivy.url = minecraftData.officialServerMappingsFile.parentFile.toURI()
                ivy.metadataSources { sources ->
                    sources.artifact()
                }
                ivy.content {
                    it.includeVersion("net.minecraft", "server-mappings", version)
                }
            }
        }
        "mojmap"
    }

    private fun createMojmapIvy() {
        project.logger.info("[Unimined] resolved $mojmapIvys")
    }

    override fun mappings(action: MappingsConfig.() -> Unit) {
        createMojmapIvy()
        if (lateActionsRunning) {
            mappings.action()
        } else {
            patcherActions.addLast {
                mappings.action()
            }
        }
    }

    override fun merged(action: MergedPatcher.() -> Unit) {
        mcPatcher = MergedMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun fabric(action: FabricLikePatcher.() -> Unit) {
        mcPatcher = OfficialFabricMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun legacyFabric(action: LegacyFabricPatcher.() -> Unit) {
        mcPatcher = LegacyFabricMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun babric(action: FabricLikePatcher.() -> Unit) {
        mcPatcher = BabricMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun quilt(action: FabricLikePatcher.() -> Unit) {
        mcPatcher = QuiltMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun flint(action: FabricLikePatcher.() -> Unit) {
        mcPatcher = FlintMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    @Deprecated("Please specify which forge.", replaceWith = ReplaceWith("minecraftForge(action)"))
    override fun forge(action: ForgeLikePatcher<*>.() -> Unit) {
        minecraftForge(action)
    }

    override fun minecraftForge(action: MinecraftForgePatcher<*>.() -> Unit) {
        mcPatcher = MinecraftForgeMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun neoForge(action: NeoForgedPatcher<*>.() -> Unit) {
        mcPatcher = NeoForgedMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun cleanroom(action: CleanroomPatcher<*>.() -> Unit) {
        mcPatcher = CleanroomMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun jarMod(action: JarModAgentPatcher.() -> Unit) {
        mcPatcher = JarModAgentMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun accessWidener(action: AccessWidenerPatcher.() -> Unit) {
        mcPatcher = AccessWidenerMinecraftTransformer.DefaultTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun accessTransformer(action: AccessTransformerPatcher.() -> Unit) {
        mcPatcher = AccessTransformerMinecraftTransformer.DefaultTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun craftBukkit(action: CraftbukkitPatcher.() -> Unit) {
        mcPatcher = CraftbukkitMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun spigot(action: SpigotPatcher.() -> Unit) {
        mcPatcher = SpigotMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun rift(action: RiftPatcher.() -> Unit) {
        mcPatcher = RiftMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    @ApiStatus.Experimental
    override fun <T: MinecraftPatcher> customPatcher(mcPatcher: T, action: T.() -> Unit) {
        this.mcPatcher = mcPatcher.also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    /**
     * The Maven group which the Minecraft dependency belongs to
     */
    open val mavenGroup: String = "net.minecraft"

    /**
     * The name for the Minecraft dependency
     */
    open val minecraftDepName: String = project.path.replace(":", "_").let { projectPath ->
        "minecraft${if (projectPath == "_") "" else projectPath}${if (sourceSet.name == "main") "" else "+"+sourceSet.name}"
    }

    override val minecraftDependency: ModuleDependency by lazy {
        project.dependencies.create(buildString {
            append("$mavenGroup:$minecraftDepName:$version")
            if (minecraftFileDev.name.endsWith("-linemapped.jar")) {
                append(":linemapped")
            }
            if (minecraftFileDev.extension != "jar") {
                append("@${minecraftFileDev.extension}")
            }
        }).also {
            project.logger.info("[Unimined/Minecraft ${project.path}:${sourceSet.name}] $minecraftDepName dependency: $it")
        } as ModuleDependency
    }

    protected val extractDependencies: MutableMap<Dependency, Extract> = mutableMapOf()

    private fun filterLibrary(lib: Library): Library? {
        val lib2 = (mcPatcher as AbstractMinecraftTransformer).libraryFilter(lib)
        if (lib2 != null) {
            for (filter in libraryReplaceMap) {
                val (replace, newLib) = filter(lib2.name)
                if (replace) {
                    if (newLib == null) {
                        return null
                    }
                    return lib2.copy(name = newLib)
                }
            }
        }
        return lib2
    }

    fun addLibraries(libraries: List<Library>) {
        for (candidate in libraries) {
            if (candidate.rules.all { it.testRule() }) {
                project.logger.info("[Unimined/Minecraft ${project.path}:${sourceSet.name}] Added dependency ${candidate.name}")
                val library = filterLibrary(candidate)
                if (library == null) {
                    project.logger.info("[Unimined/Minecraft ${project.path}:${sourceSet.name}] Excluding dependency ${candidate.name} as it is filtered by the patcher")
                    continue
                }
                val native = library.natives[OSUtils.oSId]
                if (candidate.url != null || library.downloads?.artifact != null || native == null) {
                    val dep = project.dependencies.create(library.name)
                    minecraftLibraries.dependencies.add(dep)
                    library.extract?.let { extractDependencies[dep] = it }
                }
                if (native != null) {
                    project.logger.info("[Unimined/Minecraft ${project.path}:${sourceSet.name}] Added native dependency ${candidate.name}:$native")
                    val nativeDep = project.dependencies.create("${library.name}:$native")
                    minecraftLibraries.dependencies.add(nativeDep)
                    library.extract?.let { extractDependencies[nativeDep] = it }
                }
            }
        }
    }

    private inline fun <U: JarInterface<*>> applyDefaultRemapJar(
        inputTaskName: String,
        remappingFunction: (Task, U.() -> Unit) -> TaskProvider<U>,
        crossinline defaultTaskConfiguration: Jar.() -> Unit
    ) {
        val inputTaskNameSS = inputTaskName.withSourceSet(sourceSet)

        var inputTask = project.tasks.findByName(inputTaskNameSS)
        if (inputTask == null) {
            if(createJarTask) {
                project.logger.info("[Unimined/Minecraft ${project.path}:${sourceSet.name}] Creating default $inputTaskName for $sourceSet")
                inputTask = project.tasks.create(inputTaskNameSS, Jar::class.java) {
                    it.group = "build"
                    defaultTaskConfiguration(it)
                } as Jar
            } else {
                project.logger.warn(
                    "[Unimined/Minecraft ${project.path}:${sourceSet.name}] Could not find default task '${inputTaskName.withSourceSet(sourceSet)} for $sourceSet."
                )
                project.logger.warn("[Unimined/Minecraft ${project.path}:${sourceSet.name}] add manually with `remap(task)` in the minecraft block for $sourceSet")
                return
            }
        } else if (inputTask !is Jar) {
            project.logger.warn("[Unimined/Minecraft ${project.path}:${sourceSet.name}] task $inputTaskName for $sourceSet is not an instance of ${Jar::class.qualifiedName}")
            return
        } else {
            inputTask.defaultTaskConfiguration()
        }

        val oldClassifier = inputTask.archiveClassifier.orNull
        inputTask.apply {
            archiveClassifier.set(if(!oldClassifier.isNullOrEmpty()) "$oldClassifier-dev" else "dev")
        }

        val taskProvider = remappingFunction(inputTask) {
            group = "unimined"
            description = "Remaps $inputTask's output jar"
            asJar {
                archiveClassifier.set(oldClassifier)
            }
        }
        project.tasks.getByName("build").dependsOn(taskProvider)
    }

    fun applyRunConfigs() {
        project.logger.lifecycle("[Unimined/Minecraft ${project.path}:${sourceSet.name}] Applying run configs")
        when (side) {
            EnvType.CLIENT -> {
                provideRunClientTask("client", project.file("run/client"))
            }
            EnvType.SERVER -> {
                provideRunServerTask("server", project.file("run/server"))
            }
            EnvType.COMBINED -> {
                provideRunClientTask("client", project.file("run/client"))
                provideRunServerTask("server", project.file("run/server"))
            }
            else -> {
            }
        }
    }

    override final fun replaceLibraryVersion(
        @Language("regex")
        group: String,
        @Language("regex")
        name: String,
        @Language("regex")
        classifier: String,
        version: (String) -> String?
    ) {
        if (applied) throw IllegalStateException("minecraft config already applied for $sourceSet")
        libraryReplaceMap.add { dep ->
            val match = dep.split(":")
            if (match.size == 3) {
                val (g, n, v) = match
                if (g.matches(group.toRegex()) && n.matches(name.toRegex())) {
                    true to version(v)?.let { "$g:$n:$it" }
                } else {
                    false to null
                }
            } else if (match.size == 4) {
                val (g, n, v, c) = match
                if (g.matches(group.toRegex()) && n.matches(name.toRegex()) && c.matches(classifier.toRegex())) {
                    true to version(v)?.let { "$g:$n:$c:$it" }
                } else {
                    false to null
                }
            } else {
                false to null
            }
        }
    }

    override fun libraryFilter(filter: (String) -> String?) {
        if (applied) throw IllegalStateException("minecraft config already applied for $sourceSet")
        libraryReplaceMap.add(0) { dep ->
            true to filter(dep)
        }
    }

    fun apply() {
        if (applied) return
        applied = true
        project.logger.lifecycle("[Unimined/Minecraft ${project.path}:${sourceSet.name}] Applying minecraft config for $sourceSet")

        lateActionsRunning = true

        while (patcherActions.isNotEmpty()) {
            patcherActions.removeFirst().invoke()
        }

        createMojmapIvy()

        project.logger.info("[Unimined/MappingProvider ${project.path}:${sourceSet.name}] before mappings $sourceSet")
        (mcPatcher as AbstractMinecraftTransformer).beforeMappingsResolve()

        // finalize mapping deps
        project.logger.info("[Unimined/MappingProvider ${project.path}:${sourceSet.name}] $sourceSet mappings: ${mappings.getNamespaces()}")

        // late actions done

        if (minecraft.dependencies.isNotEmpty()) {
            project.logger.warn("[Unimined/Minecraft ${project.path}:${sourceSet.name}] $minecraft dependencies are not empty!")
        }
        if (minecraftLibraries.dependencies.isNotEmpty()) {
            project.logger.warn("[Unimined/Minecraft ${project.path}:${sourceSet.name}] $minecraftLibraries dependencies are not empty!")
        }

        if (minecraftRemapper.replaceJSRWithJetbrains) {
            // inject jetbrains annotations into minecraftLibraries
            minecraftLibraries.dependencies.add(project.dependencies.create("org.jetbrains:annotations:24.0.1"))
        } else {
            // findbugs
            minecraftLibraries.dependencies.add(project.dependencies.create("com.google.code.findbugs:jsr305:3.0.2"))
        }

        // add minecraft libraries
        if (mcPatcher.addVanillaLibraries) addLibraries(minecraftData.metadata.libraries)

        if (defaultRemapJar) {
            applyDefaultRemapJar("jar", ::remap) {
                from(sourceSet.output)
                archiveClassifier.set("".withSourceSet(sourceSet))
                from(combinedWithList.map { it.second.output })
            }
        }

        if (defaultRemapSourcesJar) {
            applyDefaultRemapJar("sourcesJar", ::remapSources) {
                from(sourceSet.allSource)
                archiveClassifier.set("${"".withSourceSet(sourceSet)}-sources")
                from(combinedWithList.map { it.second.allSource })
            }
        }

        // apply minecraft patcher changes
        project.logger.lifecycle("[Unimined/Minecraft ${project.path}:${sourceSet.name}] Applying ${mcPatcher.name()}")
        (mcPatcher as AbstractMinecraftTransformer).apply()

        // create run configs
        applyRunConfigs()
        (mcPatcher as AbstractMinecraftTransformer).applyExtraLaunches()

        // finalize run configs
        runs.apply()

        // add gen sources task
        project.tasks.register("genSources".withSourceSet(sourceSet), GenSourcesTaskImpl::class.java, this).configure(consumerApply {
            group = "unimined"
            description = "Generates sources for $sourceSet's minecraft jar"
        })

        // add export mappings task
        project.tasks.register("exportMappings".withSourceSet(sourceSet), ExportMappingsTaskImpl::class.java, this.mappings).configure(consumerApply {
            group = "unimined"
            description = "Exports mappings for $sourceSet's minecraft jar"
        })

    }

    fun afterEvaluate() {
        if (!applied) throw IllegalStateException("minecraft config never applied for $sourceSet")

        // if refresh dependencies, remove sources jars
        if (project.gradle.startParameter.isRefreshDependencies || project.unimined.forceReload) {
            project.logger.lifecycle("[Unimined/Minecraft ${project.path}:${sourceSet.name}] Refreshing minecraft dependencies")
            // remove linemapped file / sources file
            val mc = getMcDevFile()
            val linemapped = mc.parent.resolve("${mc.nameWithoutExtension}-linemapped.jar")
            linemapped.deleteIfExists()
            val sources = mc.parent.resolve("${mc.nameWithoutExtension}-sources.jar")
            sources.deleteIfExists()
        }

        // create ivy repo for mc dev file / mc dev source file
        project.repositories.ivy { ivy ->
            ivy.name = "Minecraft Provider ${project.path}:${sourceSet.name}"
            ivy.patternLayout {
                it.artifact(getMcDevFile().nameWithoutExtension + "(-[classifier])(.[ext])")
            }
            ivy.url = minecraftFileDev.parentFile.toURI()
            ivy.metadataSources { sources ->
                sources.artifact()
            }
            ivy.content {
                it.includeVersion(mavenGroup, minecraftDepName, version)
            }
        }

        // remap mods
        mods.afterEvaluate()

        // add minecraft dep
        minecraft.dependencies.add(minecraftDependency)

        project.logger.info("[Unimined/MinecraftProvider ${project.path}:${sourceSet.name}] minecraft file: $minecraftFileDev")

        // run patcher after evaluate
        (mcPatcher as AbstractMinecraftTransformer).afterEvaluate()
    }

    fun getMcDevFile(): Path {
        project.logger.info("[Unimined/Minecraft ${project.path}:${sourceSet.name}] Providing minecraft dev file to $sourceSet")
        return getMinecraft(mappings.devNamespace, mappings.devFallbackNamespace).also {
            project.logger.info("[Unimined/Minecraft ${project.path}:${sourceSet.name}] Provided minecraft dev file $it")
        }
    }

    override val minecraftFileDev: File by lazy {
        val mc = getMcDevFile()
        // check if there is a -linemapped file
        val linemapped = mc.resolveSibling("${mc.nameWithoutExtension}-linemapped.jar")
        if (linemapped.exists()) {
            linemapped
        } else {
            mc
        }.toFile()
    }

    override val minecraftSourceFileDev: File? by lazy {
        // check if there is a -source file
        val source = minecraftFileDev.parentFile.resolve("${getMcDevFile().nameWithoutExtension}-sources.jar")
        if (source.exists()) {
            source
        } else {
            null
        }
    }

    override fun isMinecraftJar(path: Path) =
        minecraftFiles.values.any { it.path == path } ||
            when (side) {
                EnvType.COMBINED -> {
                    path == minecraftData.minecraftClientFile.toPath() ||
                    path == minecraftData.minecraftServerFile.toPath() ||
                    path == mergedOfficialMinecraftFile?.toPath()
                }
                EnvType.CLIENT -> {
                    path == minecraftData.minecraftClientFile.toPath()
                }
                EnvType.SERVER, EnvType.DATAGEN -> {
                    path == minecraftData.minecraftServerFile.toPath()
                }
            }



    @ApiStatus.Internal
    open fun provideRunClientTask(name: String, defaultWorkingDir: File) {
        project.logger.info("[Unimined/Minecraft ${project.path}:${sourceSet.name}] client config, $name")

        runs.preLaunch(name) {
            val nativeDir = File(properties.getValue("natives_directory").invoke())
            if (nativeDir.exists()) {
                nativeDir.deleteRecursively()
            }
            nativeDir.mkdirs()
            extractDependencies.forEach { (dep, extract) ->
                minecraftData.extract(dep, extract, nativeDir.toPath())
            }
            minecraftData.metadata.assetIndex?.let {
                AssetsDownloader.downloadAssets(project, it)
            }
        }

        val infoFile = minecraftData.mcVersionFolder
            .resolve("${version}.info")
        // TODO: replace with function to overlay betacraft version json in metadata
        if (!infoFile.exists()) {
            if (!project.gradle.startParameter.isOffline) {
                //test if betacraft has our version on file
                val url = URI.create(
                    "https://files.betacraft.uk/launcher/assets/jsons/${
                        URLEncoder.encode(
                            minecraftData.metadata.id,
                            StandardCharsets.UTF_8.name()
                        )
                    }.info"
                )
                    .toURL()
                    .openConnection() as HttpURLConnection
                url.setRequestProperty("User-Agent", "Wagyourtail/Unimined 1.0 (<wagyourtail@wagyourtail.xyz>)")
                url.requestMethod = "GET"
                url.connect()
                if (url.responseCode == 200) {
                    infoFile.writeBytes(
                        url.inputStream.readBytes(),
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.CREATE
                    )
                } else if (url.responseCode == 404) {
                    // doesn't exist
                    infoFile.writeText("")
                }
            }
        }

        val betacraftArgs = if (infoFile.exists()) {
            val properties = Properties()
            infoFile.inputStream().use { properties.load(it) }
            properties.getProperty("proxy-args")?.split(" ") ?: listOf()
        } else {
            listOf()
        }

        val assetsDir = AssetsDownloader.assetsDir(project)

        runs.configFirst(name) {
            description = "Minecraft Client"

            properties.putAll(mapOf(
                "natives_directory" to {
                    workingDir.resolve("natives").absolutePath
                },
                "library_directory" to {
                    workingDir.resolve("libraries").absolutePath
                },
                "auth_player_name" to {
                    runs.auth.authInfo?.username ?: "Dev"
                },
                "auth_uuid" to {
                    runs.auth.authInfo?.uuid?.toString() ?: UUID.nameUUIDFromBytes("OfflinePlayer:${properties.getValue("auth_player_name").invoke()}".toByteArray(StandardCharsets.UTF_8)).toString()
                },
                "game_directory" to {
                    workingDir.absolutePath
                },
                "assets_root" to {
                    assetsDir.absolutePathString()
                },
                "game_assets" to {
                    workingDir.resolve("resources").toString()
                },
                "auth_access_token" to {
                    runs.auth.authInfo?.accessToken ?: "0"
                },
                "auth_session" to {
                    runs.auth.authInfo?.accessToken ?: "0"
                },
            ))
            javaVersion = minecraftData.metadata.javaVersion
            workingDir = defaultWorkingDir
            classpath = sourceSet.runtimeClasspath
            mainClass.set(minecraftData.metadata.mainClass)
            jvmArgs(minecraftData.metadata.getJVMArgs() + betacraftArgs)
            args = minecraftData.metadata.getGameArgs()

            (mcPatcher as AbstractMinecraftTransformer).applyClientRunTransform(this)
        }
    }

    @ApiStatus.Internal
    fun provideRunServerTask(name: String, defaultWorkingDir: File) {
        project.logger.info("[Unimined/Minecraft ${project.path}:${sourceSet.name}] server config, $name")

        runs.configFirst(name) {
            description = "Minecraft Server"

            javaVersion = minecraftData.metadata.javaVersion
            workingDir = defaultWorkingDir
            classpath = sourceSet.runtimeClasspath
            minecraftData.minecraftServer.path.readZipInputStreamFor("META-INF/MANIFEST.MF", false) {
                val properties = Properties()
                properties.load(it)
                mainClass.set(properties.getProperty("Main-Class"))
            }
            args = mutableListOf("nogui")

            (mcPatcher as AbstractMinecraftTransformer).applyServerRunTransform(this)
        }
    }

    fun detectCombineWithSourceSets(): Set<Pair<Project, SourceSet>> {
        val sourceSets = mutableSetOf<Pair<Project, SourceSet>>()
        val projects = project.rootProject.allprojects
        for (project in projects) {
            for (sourceSet in project.extensions.findByType(SourceSetContainer::class.java)?.asMap?.values
                ?: listOf()) {
                if (sourceSet.output.files.intersect(this.sourceSet.runtimeClasspath.files).isNotEmpty()) {
                    sourceSets.add(project to sourceSet)
                }
            }
        }

        // ensure all combineWith's on same mappings/version
        // get current mappings
        if (project.unimined.footgunChecks) {
            val minecraftConfigs = mutableMapOf<Pair<Project, SourceSet>, MinecraftConfig?>()
            for ((project, sourceSet) in sourceSets) {
                minecraftConfigs[project to sourceSet] = project.uniminedMaybe?.minecrafts?.get(sourceSet)
            }

            for ((sourceSet, minecraftConfig) in minecraftConfigs.nonNullValues()) {
                if (mappings.devNamespace != minecraftConfig.mappings.devNamespace || mappings.devFallbackNamespace != minecraftConfig.mappings.devFallbackNamespace) {
                    throw IllegalArgumentException("All combined minecraft configs must be on the same mappings, found ${this.sourceSet} on ${mappings.devNamespace}/${mappings.devFallbackNamespace} and $sourceSet on ${minecraftConfig.mappings.devNamespace}/${minecraftConfig.mappings.devFallbackNamespace}")
                }
                if (version != minecraftConfig.version) {
                    throw IllegalArgumentException("All combined minecraft configs must be on the same version, found ${this.sourceSet} on ${version} and $sourceSet on ${minecraftConfig.version}")
                }
            }
        }

        return sourceSets
    }
}
