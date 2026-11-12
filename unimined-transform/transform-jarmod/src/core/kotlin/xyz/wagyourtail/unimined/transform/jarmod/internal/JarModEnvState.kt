package xyz.wagyourtail.unimined.transform.jarmod.internal

/**
 * Per-env jarmod DSL state collected at configuration time — the
 * 1.x patcher fields (`transforms`, `priorityClasspath`,
 * `compiletimeTransforms`) the run/transform wiring consumes.
 * Keyed by environmentId, populated by `JarModDsl` ext fns.
 */
internal object JarModEnvState {

    private val transforms = mutableMapOf<String, MutableList<String>>()
    private val priorityClasspath = mutableMapOf<String, MutableList<String>>()
    private val deleteMetaInf = mutableMapOf<String, Boolean>()
    private val customSearge = mutableMapOf<String, Boolean>()

    fun addTransforms(envId: String, entries: Iterable<String>) {
        // dedupe — env DSL blocks can evaluate more than once, and a
        // repeated entry would double-register the transform list
        val list = transforms.getOrPut(envId) { mutableListOf() }
        entries.forEach { if (it !in list) list += it }
    }

    fun addPriorityClasspath(envId: String, entries: Iterable<String>) {
        priorityClasspath.getOrPut(envId) { mutableListOf() } += entries
    }

    fun transforms(envId: String): List<String> = transforms[envId] ?: emptyList()
    fun priorityClasspath(envId: String): List<String> = priorityClasspath[envId] ?: emptyList()

    fun deleteMetaInf(envId: String): Boolean = deleteMetaInf[envId] ?: false
    fun deleteMetaInf(envId: String, value: Boolean) {
        deleteMetaInf[envId] = value
    }

    /** 1.x `ForgeLikePatcher.customSearge` — user supplies their own
     *  searge/mcp chain, so the bundled `forgeBuiltinMCP` hop must not
     *  be auto-injected (pre-1.7 shares the searge file). */
    fun customSearge(envId: String): Boolean = customSearge[envId] ?: false
    fun customSearge(envId: String, value: Boolean) {
        customSearge[envId] = value
    }

    /** Opt-in: resolve `org.ow2.asm:asm-all:5.2` for FML's asm-all
     *  dyn lib instead of the bundled/maven 4.x — Java 8 classfile
     *  support in dev. The resolved jar is written to `runDir/lib`
     *  under the name `CoreFMLLibraries` asks for. */
    fun useAsm52(envId: String): Boolean = useAsm52[envId] ?: true
    fun useAsm52(envId: String, value: Boolean) {
        useAsm52[envId] = value
    }

    private val useAsm52 = mutableMapOf<String, Boolean>()

    private val refmaps = mutableMapOf<String, MutableList<String>>()
    private val initializers = mutableMapOf<String, MutableList<String>>()
    private val failStrategy = mutableMapOf<String, String>()
    private val debug = mutableMapOf<String, Boolean>()

    /** `jma.refmaps` — classpath-resource names of refmap JSON files
     *  (agent merges all listed maps per transformer). */
    fun addRefmaps(envId: String, entries: Iterable<String>) {
        val list = refmaps.getOrPut(envId) { mutableListOf() }
        entries.forEach { if (it !in list) list += it }
    }

    fun refmaps(envId: String): List<String> = refmaps[envId] ?: emptyList()

    /** `jma.initializers` — `JMAInitializer` class names, run before
     *  manifest-declared initializers (list order is load order). */
    fun addInitializers(envId: String, entries: Iterable<String>) {
        val list = initializers.getOrPut(envId) { mutableListOf() }
        entries.forEach { if (it !in list) list += it }
    }

    fun initializers(envId: String): List<String> = initializers[envId] ?: emptyList()

    /** `jma.failStrategy` — ClassTransform `FailStrategy` name
     *  (CONTINUE/CANCEL/EXIT/THROW); agent ≥ 0.1.5. */
    fun failStrategy(envId: String): String? = failStrategy[envId]
    fun failStrategy(envId: String, value: String) {
        failStrategy[envId] = value
    }

    /** `jma.debug` — dump patched classes under `.jma/patched`. */
    fun debug(envId: String): Boolean = debug[envId] ?: false
    fun debug(envId: String, value: Boolean) {
        debug[envId] = value
    }

    private val compiletimeTransforms = mutableMapOf<String, Boolean>()

    /** 1.x `JarModAgentPatcher.compiletimeTransforms` — run the env's
     *  declared jma transformers over the mod jar during `remapJar`
     *  (a forked JarModAgent main pass before the namespace remap)
     *  instead of relying on the runtime `-javaagent` alone. */
    fun compiletimeTransforms(envId: String): Boolean =
        compiletimeTransforms[envId] ?: false
    fun compiletimeTransforms(envId: String, value: Boolean) {
        compiletimeTransforms[envId] = value
    }

}
