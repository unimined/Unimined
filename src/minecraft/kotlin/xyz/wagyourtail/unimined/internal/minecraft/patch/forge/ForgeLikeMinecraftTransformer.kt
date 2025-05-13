package xyz.wagyourtail.unimined.internal.minecraft.patch.forge

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.TaskContainer
import org.gradle.jvm.tasks.Jar
import org.jetbrains.annotations.ApiStatus
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.minecraft.patch.ataw.AccessConvert
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.ForgeLikePatcher
import xyz.wagyourtail.unimined.api.minecraft.task.AbstractRemapJarTask
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.api.uniminedMaybe
import xyz.wagyourtail.unimined.internal.mapping.at.AccessTransformerApplier
import xyz.wagyourtail.unimined.internal.mapping.task.ExportMappingsTaskImpl
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.AbstractMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.access.AccessConvertImpl
import xyz.wagyourtail.unimined.internal.minecraft.patch.access.transformer.AccessTransformerMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.access.transformer.AccessTransformerMinecraftTransformer.Companion.getDefaultDependency
import xyz.wagyourtail.unimined.internal.minecraft.patch.access.transformer.AccessTransformerMinecraftTransformer.Companion.getDependencyMainClass
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.FG3MinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod.JarModAgentMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod.JarModMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.resolver.Library
import xyz.wagyourtail.unimined.internal.minecraft.transform.merge.ClassMerger
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.mcp.v6.MCPv6FieldWriter
import xyz.wagyourtail.unimined.mapping.formats.mcp.v6.MCPv6MethodWriter
import xyz.wagyourtail.unimined.mapping.formats.mcpzip.MCPZipWriter
import xyz.wagyourtail.unimined.mapping.formats.srg.SrgWriter
import xyz.wagyourtail.unimined.util.*
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Path
import kotlin.io.path.createDirectories

abstract class ForgeLikeMinecraftTransformer(
    project: Project,
    provider: MinecraftProvider,
    providerName: String,
): AbstractMinecraftTransformer(project, provider, providerName), ForgeLikePatcher<JarModMinecraftTransformer>, AccessTransformerMinecraftTransformer, AccessConvert by AccessConvertImpl(project, provider) {


    private var useToolchainsIntl by FinalizeOnRead(true)

    override var useToolchains: Boolean
        get() {
            val transformer = forgeTransformer
            return if (transformer is FG3MinecraftTransformer) {
                transformer.mcpConfigRunner.useToolchains
            } else {
                useToolchainsIntl
            }
        }
        set(value) {
            if (forgeTransformer is FG3MinecraftTransformer) {
                (forgeTransformer as FG3MinecraftTransformer).mcpConfigRunner.useToolchains = value
            } else {
                useToolchainsIntl = value
            }
        }

    val forge: Configuration = project.configurations.maybeCreate("forge".withSourceSet(provider.sourceSet)).apply {
        isTransitive = false
    }


    // access transformer fields
    override var accessTransformer: File? by FinalizeOnRead(null)
    override var accessTransformerPaths: List<String> by FinalizeOnRead(listOf())
    override var atDependency: Dependency by FinalizeOnRead(getDefaultDependency(project, provider))
    override var atMainClass: String by FinalizeOnRead(getDependencyMainClass(project, provider))
    override var legacyATFormat: Boolean by FinalizeOnRead(false)

    override var customSearge: Boolean by FinalizeOnRead(false)

    override var canCombine: Boolean
        get() = super.canCombine
        set(value) {
            super.canCombine = value
        }

    override var onMergeFail: (clientNode: ClassNode, serverNode: ClassNode, fs: ZipArchiveOutputStream, exception: Exception) -> Unit
        get() = forgeTransformer.onMergeFail
        set(value) {
            forgeTransformer.onMergeFail = value
        }

    override var unprotectRuntime: Boolean
        get() = forgeTransformer.unprotectRuntime
        set(value) {
            forgeTransformer.unprotectRuntime = value
        }


    protected abstract fun addMavens()

    init {
        addMavens()
        legacyATFormat = provider.minecraftData.mcVersionCompare(provider.version, "1.7.10") < 0
        provider.minecraftRemapper.addResourceRemapper { from, to ->
            runBlocking {
                AccessTransformerApplier.AtRemapper(
                    project.logger,
                    from,
                    to,
                    mappings = provider.mappings.resolve(),
                    isLegacy = legacyATFormat
                )
            }
        }
    }

    fun transforms(transform: String) {
        if (forgeTransformer !is JarModAgentMinecraftTransformer) {
            throw IllegalStateException("JarModAgentPatcher is not available")
        }
        (forgeTransformer as JarModAgentMinecraftTransformer).transforms(transform)
    }

    fun transforms(transforms: List<String>) {
        if (forgeTransformer !is JarModAgentMinecraftTransformer) {
            throw IllegalStateException("JarModAgentPatcher is not available")
        }
        (forgeTransformer as JarModAgentMinecraftTransformer).transforms(transforms)
    }

    override var prodNamespace: Namespace
        get() = forgeTransformer.prodNamespace
        set(value) {
            forgeTransformer.prodNamespace = value
        }

    override var deleteMetaInf: Boolean
        get() = forgeTransformer.deleteMetaInf
        set(value) {
            forgeTransformer.deleteMetaInf = value
        }

    override var mixinConfig: List<String> = mutableListOf()

    private val sideMarkers = mapOf(
        "net/minecraftforge/fml/relauncher/SideOnly" to Triple(
            "net/minecraftforge/fml/relauncher/Side", "value", mapOf(
                EnvType.CLIENT to "CLIENT",
                EnvType.SERVER to "SERVER"
            )
        ),
        "cpw/mods/fml/relauncher/SideOnly" to Triple(
            "cpw/mods/fml/relauncher/Side", "value", mapOf(
                EnvType.CLIENT to "CLIENT",
                EnvType.SERVER to "SERVER"
            )
        ),
        "cpw/mods/fml/common/asm/SideOnly" to Triple(
            "cpw/mods/fml/common/Side", "value", mapOf(
                EnvType.CLIENT to "CLIENT",
                EnvType.SERVER to "SERVER"
            )
        ),
    )

    override val remapAtToLegacy: Boolean by lazy {
        provider.minecraftData.mcVersionCompare(provider.version, "1.7.10") < 0
    }

    override fun beforeMappingsResolve() {
        forgeTransformer.beforeMappingsResolve()
    }

    private val actualSideMarker by lazy {
        if (forge.dependencies.isEmpty()) return@lazy null // pre 1.3 - see below
        val forgeUniversal = forge.dependencies.last()
        val forgeJar = forge.getFiles(forgeUniversal) { it.extension == "zip" || it.extension == "jar" }.singleFile

        val type = forgeJar.toPath().readZipContents().map {
            it.substringBefore(".class") to sideMarkers[it.substringBefore(".class")]
        }.filter { it.second != null }.map { it.first to it.second!! }
        if (type.size > 1) throw IllegalStateException("Found more than one side marker in forge jar: $type")
        if (type.isEmpty()) {
            project.logger.warn("[Unimined/ForgeTransformer] No side marker found in forge jar, using default (none)")
            return@lazy null
        }
        type.first()
    }

    private fun applyAnnotationVisitor(visitor: AnnotationVisitor, env: EnvType) {
        if (actualSideMarker == null) return
        visitor.visitEnum(
            actualSideMarker!!.second.second,
            "L${actualSideMarker!!.second.first};",
            actualSideMarker!!.second.third[env]
        )
        visitor.visitEnd()
    }

    override val merger: ClassMerger = ClassMerger(
        { node, env ->
            if (env == EnvType.JOINED) return@ClassMerger
            if (actualSideMarker == null) return@ClassMerger
            // already has
            if (node.visibleAnnotations?.any { it.desc == "L${actualSideMarker!!.first};" } == true) return@ClassMerger
            // anonymous class
            if (isAnonClass(node)) return@ClassMerger
            val visitor = node.visitAnnotation("L${actualSideMarker!!.first};", true)
            applyAnnotationVisitor(visitor, env)
        },
        { node, env ->
            if (env == EnvType.JOINED) return@ClassMerger
            if (actualSideMarker == null) return@ClassMerger
            if (node.visibleAnnotations?.any { it.desc == "L${actualSideMarker!!.first};" } == true) return@ClassMerger
            val visitor = node.visitAnnotation("L${actualSideMarker!!.first};", true)
            applyAnnotationVisitor(visitor, env)
        },
        { node, env ->
            if (env == EnvType.JOINED) return@ClassMerger
            if (actualSideMarker == null) return@ClassMerger
            if (node.visibleAnnotations?.any { it.desc == "L${actualSideMarker!!.first};" } == true) return@ClassMerger
            val visitor = node.visitAnnotation("L${actualSideMarker!!.first};", true)
            applyAnnotationVisitor(visitor, env)
        }
    )


    @ApiStatus.Internal
    var tweakClassClient: String? = null

    @ApiStatus.Internal
    var tweakClassServer: String? = null

    @ApiStatus.Internal
    internal var mainClass: String? = null

    @get:ApiStatus.Internal
    @set:ApiStatus.Experimental
    var srgToMCPAsSRG: Path by FinalizeOnRead(LazyMutable {
        provider.localCache.resolve("mappings").createDirectories().resolve("srg2mcp.srg").apply {
            val export = ExportMappingsTaskImpl.ExportImpl(provider.mappings).apply {
                location = toFile()
                type = SrgWriter
                sourceNamespace = provider.mappings.checkedNs("searge")
                targetNamespace = setOf(provider.mappings.devNamespace)
            }
            export.validate()
            runBlocking {
                export.exportFunc(provider.mappings.resolve())
            }
        }
    })

    @get:ApiStatus.Internal
    @set:ApiStatus.Experimental
    var srgToMCPAsMCP: Path by FinalizeOnRead(LazyMutable {
        provider.localCache.resolve("mappings").createDirectories().resolve("srg2mcp.jar").apply {
            MCPv6FieldWriter.writeComments = false
            MCPv6MethodWriter.writeComments = false
            val export = ExportMappingsTaskImpl.ExportImpl(provider.mappings).apply {
                location = toFile()
                type = MCPZipWriter
                sourceNamespace = provider.mappings.checkedNs("searge")
                skipComments = true // the reader forge uses now is too dumb...
                targetNamespace = setOf(provider.mappings.devNamespace)
                envType = xyz.wagyourtail.unimined.mapping.EnvType.JOINED
            }
            export.validate()
            runBlocking {
                export.exportFunc(provider.mappings.resolve())
            }
            MCPv6FieldWriter.writeComments = true
            MCPv6MethodWriter.writeComments = true
        }
    })

    init {
        project.unimined.minecraftForgeMaven()
    }

    open val versionJsonJar by lazy {
        val forgeDep = forge.dependencies.first()
        forge.getFiles(forgeDep) { it.extension == "zip" || it.extension == "jar" }.singleFile
    }

    override fun prodNamespace(namespace: String) {
        forgeTransformer.prodNamespace(namespace)
    }

    override fun apply() {

        // test if pre unified jar
        if (provider.minecraftData.mcVersionCompare(provider.version, "1.3") > 0) {
            //parse version json from universal jar and apply
            versionJsonJar.toPath().readZipInputStreamFor("version.json", false) {
                JsonParser.parseReader(InputStreamReader(it)).asJsonObject
            }?.let { versionJson ->
                parseVersionJson(versionJson)
            }
        }

        if (mixinConfig.isNotEmpty()) {
            val task = project.tasks.findByName("jar".withSourceSet(provider.sourceSet))
            if (task != null && task is Jar) {
                task.manifest.attributes["MixinConfigs"] = mixinConfig.joinToString(",")
            }
        }

        forgeTransformer.apply()

        super.apply()
    }

    abstract fun parseVersionJson(json: JsonObject)

    override fun merge(clientjar: MinecraftJar, serverjar: MinecraftJar): MinecraftJar {
        return forgeTransformer.merge(clientjar, serverjar)
    }

    override fun transform(minecraft: MinecraftJar): MinecraftJar {
        return forgeTransformer.transform(minecraft)
    }

    enum class ForgeFiles(val path: String) {
        FORGE_AT("META-INF/accesstransformer.cfg"),
        OLD_FORGE_AT("forge_at.cfg"),
        BINPATCHES_PACK("binpatches.pack.lzma"),
        JAR_PATCHES("net/minecraft/client/Minecraft.class"),
        VERSION_JSON("version.json"),
        ;

        companion object {
            val ffMap = mutableMapOf<String, ForgeFiles>()

            init {
                for (entry in ForgeFiles.values()) {
                    ffMap[entry.path] = entry
                }
            }
        }
    }

    override fun libraryFilter(library: Library): Library? {
        return forgeTransformer.libraryFilter(library)
    }

    enum class ForgeVersion(
        val accept: Set<ForgeFiles> = setOf(),
        val deny: Set<ForgeFiles> = setOf(),
    ) {
        FG1(
            setOf(
                ForgeFiles.JAR_PATCHES
            ),
            setOf(
                ForgeFiles.FORGE_AT,
                ForgeFiles.BINPATCHES_PACK,
                ForgeFiles.VERSION_JSON,
            )
        ),
        FG2(
            setOf(
                ForgeFiles.OLD_FORGE_AT,
                ForgeFiles.BINPATCHES_PACK,
                ForgeFiles.VERSION_JSON
            ),
            setOf(
                ForgeFiles.JAR_PATCHES,
                ForgeFiles.FORGE_AT
            )
        ),
        FG3(
            setOf(
            ),
            setOf(
                ForgeFiles.JAR_PATCHES,
                ForgeFiles.VERSION_JSON
            )
        ),
    }

    override fun afterRemap(baseMinecraft: MinecraftJar): MinecraftJar {
        return super<AbstractMinecraftTransformer>.afterRemap(super<AccessTransformerMinecraftTransformer>.afterRemap(forgeTransformer.afterRemap(baseMinecraft)))
    }

    val groups: String by lazy {
        val groups = sortProjectSourceSets().mapValues { it.value.toMutableSet() }.toMutableMap()
        groups.putIfAbsent(this.project to this.provider.sourceSet, mutableSetOf())

        // detect non-forge groups
        for ((proj, sourceSet) in groups.keys.toSet()) {
            if (proj.uniminedMaybe?.minecrafts?.get(sourceSet)?.mcPatcher !is ForgeLikePatcher<*>) {
                // merge with current
                proj.logger.warn("[Unimined/ForgeLike] Non-forge ${(proj to sourceSet).toPath()} found in forge classpath groups, merging with current (${(project to provider.sourceSet).toPath()}), this should've been manually specified with `combineWith`")
                groups[this.project to this.provider.sourceSet]!! += groups[proj to sourceSet]!!
                groups.remove(proj to sourceSet)
            }
        }
        project.logger.info("[Unimined/ForgeLike] Classpath groups: ${groups.map { it.key.toPath() + " -> " + it.value.joinToString(", ") { it.toPath() } }.joinToString("\n    ")}")
        groups.map { entry -> entry.value.flatMap { listOf(it.second.output.resourcesDir) + it.second.output.classesDirs }.joinToString(File.pathSeparator) { "${entry.key.toPath().replace(":", "_")}%%${it!!.absolutePath}" } }.joinToString(File.pathSeparator)
    }

    override fun applyClientRunTransform(config: RunConfig) {
        project.logger.info("[Unimined/ForgeTransformer] Adding mixin config $mixinConfig to client run config")
        forgeTransformer.applyClientRunTransform(config)
        for (mixin in mixinConfig) {
            config.args("--mixin", mixin)
        }
    }

    override fun applyServerRunTransform(config: RunConfig) {
        project.logger.info("[Unimined/ForgeTransformer] Adding mixin config $mixinConfig to server run config")
        forgeTransformer.applyServerRunTransform(config)
        for (mixin in mixinConfig) {
            config.args("--mixin", mixin)
        }
    }

    override fun afterRemapJarTask(remapJarTask: AbstractRemapJarTask, output: Path) {
        forgeTransformer.afterRemapJarTask(remapJarTask, output)
    }

    override fun afterEvaluate() {
        forgeTransformer.afterEvaluate()
    }

    override fun applyExtraLaunches() {
        forgeTransformer.applyExtraLaunches()
    }

    private fun applyToTask(container: TaskContainer) {
        for (jar in container.withType(Jar::class.java)) {
            jar.manifest {
                it.attributes["MixinConfigs"] = mixinConfig.joinToString(",")
            }
        }
    }

    override fun name(): String {
        return forgeTransformer.name()
    }

    override fun beforeRemapJarTask(remapJarTask: AbstractRemapJarTask, input: Path): Path {
        return forgeTransformer.beforeRemapJarTask(remapJarTask, input)
    }

    override fun configureRemapJar(task: AbstractRemapJarTask) {
        forgeTransformer.configureRemapJar(task)
    }

    override fun createSourcesJar(
        classpath: FileCollection,
        patchedJar: Path,
        outputPath: Path,
        linemappedPath: Path?,
        side: EnvType
    ) {
        forgeTransformer.createSourcesJar(classpath, patchedJar, outputPath, linemappedPath, side)
    }

}