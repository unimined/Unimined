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
        transforms.getOrPut(envId) { mutableListOf() } += entries
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
    fun useAsm52(envId: String): Boolean = useAsm52[envId] ?: false
    fun useAsm52(envId: String, value: Boolean) {
        useAsm52[envId] = value
    }

    private val useAsm52 = mutableMapOf<String, Boolean>()

}
