package xyz.wagyourtail.unimined.internal.mapping.extension

import com.google.gson.JsonObject
import net.fabricmc.tinyremapper.InputTag
import net.fabricmc.tinyremapper.TinyRemapper
import net.fabricmc.tinyremapper.api.TrClass
import net.fabricmc.tinyremapper.api.TrEnvironment
import net.fabricmc.tinyremapper.extension.mixin.common.Logger
import net.fabricmc.tinyremapper.extension.mixin.common.data.Annotation
import net.fabricmc.tinyremapper.extension.mixin.common.data.CommonData
import net.fabricmc.tinyremapper.extension.mixin.common.data.Constant
import org.gradle.api.logging.LogLevel
import org.jetbrains.annotations.ApiStatus
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import xyz.wagyourtail.unimined.api.mapping.mixin.MixinRemapOptions
import xyz.wagyourtail.unimined.internal.mapping.extension.jma.JarModAgentMetaData
import xyz.wagyourtail.unimined.internal.mapping.extension.jma.hard.JMAHard
import xyz.wagyourtail.unimined.internal.mapping.extension.jma.refmap.JMARefmap
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.OfficialMixinMetaData
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.hard.BaseMixinHard
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.hard.HardTargetRemappingClassVisitor
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.refmap.BaseMixinRefmap
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.refmap.RefmapBuilderClassVisitor
import xyz.wagyourtail.unimined.internal.mapping.extension.mixinextra.MixinExtra
import xyz.wagyourtail.unimined.util.DefaultMap
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.defaultedMapOf
import java.nio.file.FileSystem
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedDeque

class MixinRemapExtension(
    loggerLevel: LogLevel = LogLevel.WARN,
    allowImplicitWildcards: Boolean = false,
) : PerInputTagExtension<MixinRemapExtension.MixinTarget>(), MixinRemapOptions {

    val allowImplicitWildcards by FinalizeOnRead(allowImplicitWildcards)

    override fun register(tag: InputTag): MixinTarget {
        return MixinTarget(tag, this).apply {
            metadataReader.forEach {
                this.addMetadata(it(this@MixinRemapExtension))
            }
        }
    }

    private var metadataReader = mutableListOf<(MixinRemapExtension) -> MixinMetadata>()
    private var modifyHardRemapper: (HardTargetRemappingClassVisitor) -> Unit = {}
    private var modifyRefmapBuilder: (RefmapBuilderClassVisitor) -> Unit = {}


    val logger: Logger = Logger(
        translateLogLevel(
            loggerLevel
        )
    )

    var off by FinalizeOnRead(false)
    var noRefmap: Set<String> by FinalizeOnRead(setOf())

    @ApiStatus.Internal
    fun modifyMetadataReader(modifier: (MixinRemapExtension) -> MixinMetadata) {
        metadataReader.add(modifier)
    }

    @ApiStatus.Internal
    fun modifyHardRemapper(modifier: (HardTargetRemappingClassVisitor) -> Unit) {
        val old = modifyHardRemapper
        modifyHardRemapper = { old(it); modifier(it) }
    }

    @ApiStatus.Internal
    fun modifyRefmapBuilder(modifier: (RefmapBuilderClassVisitor) -> Unit) {
        val old = modifyRefmapBuilder
        modifyRefmapBuilder = { old(it); modifier(it) }
    }

    override fun off() {
        reset()
        off = true
    }

    override fun disableRefmap() {
        disableRefmap(listOf("BaseMixin", "JarModAgent"))
    }

    override fun disableRefmap(keys: List<String>) {
        noRefmap = keys.toSet()
    }

    override fun enableMixinExtra() {
        modifyRefmapBuilder(MixinExtra::refmapBuilder)
    }

    override fun enableBaseMixin() {
        modifyMetadataReader(::OfficialMixinMetaData)
        modifyHardRemapper(BaseMixinHard::hardRemapper)
        modifyRefmapBuilder(BaseMixinRefmap::refmapBuilder)
    }

    override fun enableJarModAgent() {
        modifyMetadataReader(::JarModAgentMetaData)
        modifyHardRemapper(JMAHard::hardRemapper)
        modifyRefmapBuilder(JMARefmap::refmapBuilder)
    }

    override fun resetMetadataReader() {
        metadataReader = mutableListOf()
    }

    override fun resetHardRemapper() {
        modifyHardRemapper = {}
    }

    override fun resetRefmapBuilder() {
        modifyRefmapBuilder = {}
    }


    override fun reset() {
        off = false
        noRefmap = setOf()
        resetMetadataReader()
        resetHardRemapper()
        resetRefmapBuilder()
    }


    open class MixinTarget(override val inputTag: InputTag, val extension: MixinRemapExtension) : InputTagExtension {
        protected val metadata: MergedMetadata = MergedMetadata(extension)
        protected val tasks: DefaultMap<Int, Deque<(CommonData) -> Unit>> = defaultedMapOf { ConcurrentLinkedDeque() }

        fun addMetadata(metadata: MixinMetadata) {
            this.metadata.addMetadata(metadata)
        }

        override fun readInput(remapper: TinyRemapper, vararg input: Path): CompletableFuture<*> {
            return metadata.readInput(*input).thenCompose { super.readInput(remapper, *input) }
        }

        override fun analyzeVisitor(mrjVersion: Int, className: String, next: ClassVisitor): ClassVisitor {
            try {
                return if (metadata.contains(dot(className))) {
                    val refmap = metadata.getExistingRefmapFor(dot(className))
                    val existing = refmap?.get("mappings")?.asJsonObject?.get(className)?.asJsonObject ?: JsonObject()
                    val mappings = mutableMapOf<String, String>()
                    existing.entrySet().forEach {
                        mappings[it.key] = it.value.asString
                    }
                    extension.logger.info("[HardTarget] found mixin class $className")
                    if (mappings.isNotEmpty()) {
                        extension.logger.info("[HardTarget] existing mappings $mappings")
                    }
                    val visitor = HardTargetRemappingClassVisitor(
                        next,
                        className,
                        mappings,
                        extension.logger
                    )
                    extension.modifyHardRemapper(visitor)
                    synchronized(tasks) {
                        tasks[mrjVersion].add(visitor::runRemap)
                    }
                    visitor
                } else {
                    if (!extension.off) {
                        val visitor = HardTargetRemappingClassVisitor(
                            next,
                            className,
                            emptyMap(),
                            extension.logger
                        )
                        extension.modifyHardRemapper(visitor)
                        synchronized(tasks) {
                            tasks[mrjVersion].add(visitor::runRemap)
                        }
                        return visitor
                    } else {
                        return next
                    }
                }
            } catch (e: Exception) {
                throw IllegalStateException("Error while processing class $className: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }

        override fun stateProcessor(environment: TrEnvironment) {
            extension.logger.info("[HardTarget] processing state for ${environment.mrjVersion}")
            val data = CommonData(environment, extension.logger)
            try {
                for (task in tasks[environment.mrjVersion]) {
                    task(data)
                }
            } catch (e: Exception) {
                throw IllegalStateException("Error while processing state for ${environment.mrjVersion}: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }

        override fun preApplyVisitor(cls: TrClass, next: ClassVisitor): ClassVisitor {
            try {
                return if (metadata.contains(dot(cls.name))) {
                    val refmap = metadata.getExistingRefmapFor(dot(cls.name))
                    val existing = refmap?.get("mappings")?.asJsonObject?.get(cls.name)?.asJsonObject ?: JsonObject()
                    val mappings = mutableMapOf<String, String>()
                    existing.entrySet().forEach {
                        mappings[it.key] = it.value.asString
                    }
                    extension.logger.info("[RefmapBuilder] found mixin class ${cls.name}")
                    if (mappings.isNotEmpty()) {
                        extension.logger.info("[RefmapBuilder] existing mappings $mappings")
                    }
                    val target = JsonObject()
                    val visitor = RefmapBuilderClassVisitor(
                        CommonData(cls.environment, extension.logger),
                        cls.name,
                        target,
                        next,
                        mappings,
                        extension,
                        onEnd = {
                            if (target.size() > 0) {
                                extension.logger.info("[RefmapBuilder] adding ${target.size()} mappings for ${cls.name}")
                                synchronized(metadata) {
                                    val refmapJson = metadata.getRefmapFor(dot(cls.name))
                                    if (!refmapJson.containsKey("mappings")) {
                                        refmapJson["mappings"] = TreeMap<String, Any>()
                                    }
                                    val refmapMappings = refmapJson["mappings"] as TreeMap<String, Any>
                                    refmapMappings[cls.name] = target
                                }
                            }
                        },
                        allowImplicitWildcards = extension.allowImplicitWildcards
                    )
                    extension.modifyRefmapBuilder(visitor)
                    visitor
                } else {
                    if (!extension.off) {
                        val target = JsonObject()
                        val visitor = RefmapBuilderClassVisitor(
                            CommonData(cls.environment, extension.logger),
                            cls.name,
                            target,
                            next,
                            emptyMap(),
                            extension,
                            onEnd = {
                                if (target.size() > 0) {
                                    extension.logger.info("[RefmapBuilder] adding ${target.size()} mappings for ${cls.name}")
                                    synchronized(metadata) {
                                        val refmapJson = metadata.fallbackRefmap()
                                        if (!refmapJson.containsKey("mappings")) {
                                            refmapJson["mappings"] = TreeMap<String, Any>()
                                        }
                                        val refmapMappings = refmapJson["mappings"] as TreeMap<String, Any>
                                        refmapMappings[cls.name] = target
                                    }
                                }
                            },
                            allowImplicitWildcards = extension.allowImplicitWildcards
                        )
                        extension.modifyRefmapBuilder(visitor)
                        object : ClassVisitor(Constant.ASM_VERSION, visitor) {
                            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                                if (descriptor == Annotation.MIXIN) {
                                    extension.logger.warn("[RefmapBuilder] found mixin class ${cls.name} without entry!")
                                }
                                return super.visitAnnotation(descriptor, visible)
                            }
                        }
                    } else {
                        next
                    }
                }
            } catch (e: Exception) {
                throw IllegalStateException("Error while processing class ${cls.name}: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }

        override fun insertExtra(fs: FileSystem) {
            metadata.writeExtra(fs)
        }

    }

    abstract class MixinMetadata(val parent: MixinRemapExtension) {

        abstract fun readInput(vararg input: Path): CompletableFuture<*>

        abstract fun contains(className: String): Boolean
        abstract fun getRefmapFor(className: String): TreeMap<String, Any>

        abstract fun writeExtra(fs: FileSystem)
        abstract fun getExistingRefmapFor(className: String): JsonObject?

        abstract fun fallbackRefmap(): TreeMap<String, Any>
    }

    class MergedMetadata(parent: MixinRemapExtension) : MixinMetadata(parent) {
        val metadata: MutableSet<MixinMetadata> = mutableSetOf()

        fun addMetadata(metadata: MixinMetadata) {
            this.metadata.add(metadata)
        }

        override fun readInput(vararg input: Path): CompletableFuture<*> {
            return CompletableFuture.allOf(*metadata.map { it.readInput(*input) }.toTypedArray())
        }

        override fun contains(className: String): Boolean {
            return metadata.any { it.contains(className) }
        }

        override fun getRefmapFor(className: String): TreeMap<String, Any> {
            return metadata.first { it.contains(className) }.getRefmapFor(className)
        }

        override fun writeExtra(fs: FileSystem) {
            metadata.forEach { it.writeExtra(fs) }
        }

        override fun getExistingRefmapFor(className: String): JsonObject? {
            return metadata.firstOrNull { it.contains(className) }?.getExistingRefmapFor(className)
        }

        override fun fallbackRefmap(): TreeMap<String, Any> {
            return metadata.first().fallbackRefmap()
        }

    }

    companion object {
        fun dot(name: String): String = name.replace('/', '.')


        fun translateLogLevel(loggerLevel: LogLevel) = when (loggerLevel) {
            LogLevel.DEBUG -> Logger.Level.INFO
            LogLevel.INFO -> Logger.Level.INFO
            LogLevel.WARN -> Logger.Level.WARN
            LogLevel.ERROR -> Logger.Level.ERROR
            LogLevel.QUIET -> Logger.Level.ERROR
            else -> Logger.Level.WARN
        }
    }

}