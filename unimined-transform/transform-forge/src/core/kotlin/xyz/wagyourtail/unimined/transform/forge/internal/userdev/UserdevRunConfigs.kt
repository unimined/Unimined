package xyz.wagyourtail.unimined.transform.forge.internal.userdev

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevRunConfigs.groupsString
import xyz.wagyourtail.unimined.transform.forge.internal.UserdevConfig

/**
 * `runs.<side>` interpretation — 1.x `FG3MinecraftTransformer`
 * `applyClientRunTransform`/`applyServerRunTransform` ported to a pure
 * interpreter. Produces a launch description the runs wiring applies;
 * environment-provided values (asset index, srg-to-mcp path, source
 * roots, resolved module list) come in via [Ctx].
 *
 * Two shapes:
 *  - **legacydev** (`main` starts `net.minecraftforge.legacydev`) —
 *    launchwrapper + `GradleStart` srg-mcp + `FML*Tweaker`
 *  - **modern** — `main`/`args`/`jvmArgs`/`env`/`props` from the run
 *    object, `{x}` placeholders via [Ctx.argValue]
 */
internal object UserdevRunConfigs {

    /** A resolved `runs.<side>` launch description. */
    data class RunSpec(
        /** Final main class (launchwrapper for legacydev,
         *  `UnionRelauncher` when relaunching). */
        val mainClass: String,
        /** Program args (`--tweakClass ...` for legacydev). */
        val args: List<String>,
        /** `-X`/`-D` jvm args — props flattened to `-Dkey=value`. */
        val jvmArgs: List<String>,
        /** Environment variables (`FORGE_SPEC`, `MOD_CLASSES`, `env`). */
        val environment: Map<String, String>,
        /** `env.tweakClass` — feeds the environment's tweak-class field
         *  (`null` when the run object doesn't declare one). */
        val tweakClass: String?,
        /** Legacydev shape — caller applies launchwrapper semantics. */
        val legacyDev: Boolean,
    )

    /**
     * Injectable environment values for `{x}` placeholders:
     *  - `{asset_index}` → [assetIndex]
     *  - `{mcp_mappings}` → `"unimined.stub"`
     *  - `{natives}` → `\${natives_directory}`
     *  - `{source_roots}` → [sourceRoots]
     *  - `{modules}` → [modules] joined by path separator
     *  - other `{x}` → `\$x` (deferred env-var reference, 1.x parity)
     */
    class Ctx(
        val assetIndex: () -> String = { "" },
        /**
         * Forge classpath groups — 1.x `parent.groups`. Key = the
         * group's `(project, sourceSet).toPath()` (e.g. `:sub:main`);
         * values = each member source set's `resourcesDir` +
         * `classesDirs` (resources first). Rendered by
         * [UserdevRunConfigs.groupsString]; `MOD_CLASSES` and
         * `{source_roots}` both resolve to it.
         */
        val groups: () -> Map<String, List<String>> = { emptyMap() },
        val modules: () -> List<String> = { emptyList() },
        /** `GradleStart.srg.srg-mcp` path for legacydev `-D` args. */
        val srgToMcpAsSrg: () -> String = { "" },
        /** 1.x `properties.minecraft_classpath_file` — the legacy
         *  classpath listing path. */
        val minecraftClasspathFile: () -> String = { "" },
        /**
         * Separator inside the MOD_CLASSES/`{source_roots}` string —
         * `File.pathSeparator` normally, but `;` when forge's
         * `bootstrap-dev <2.1.4` is on the classpath (it splits on
         * `;` literally, arch-loom `getSourceRootsSeparator`).
         */
        val groupSeparator: String = java.io.File.pathSeparator
    ) {
        fun argValue(arg: String): String = when {
            !arg.startsWith("{") -> arg
            arg == "{asset_index}" -> assetIndex()
            arg == "{mcp_mappings}" -> "unimined.stub"
            arg == "{natives}" -> "\${natives_directory}"
            arg == "{source_roots}" -> groupsString()
            arg == "{minecraft_classpath_file}" -> minecraftClasspathFile()
            arg == "{modules}" -> modules().joinToString(
                java.io.File.pathSeparator
            )

            else -> "\$$arg"
        }
    }

    /**
     * 1.x `ForgeLikeMinecraftTransformer.groups` format: each group
     * contributes `groupKey%%dir` per dir (the group's
     * `(project, sourceSet)` path with `:` flattened to `_`),
     * `File.pathSeparator`-joined across all dirs and groups — the
     * dev runtime reads `group%%dir` pairs to attribute classpath
     * dirs to their forge group.
     */
    fun groupsString(
        groups: Map<String, List<String>>,
        separator: String = java.io.File.pathSeparator
    ): String =
        groups.map { (key, dirs) ->
            dirs.joinToString(separator) {
                "${key.replace(":", "_")}%%$it"
            }
        }.joinToString(separator)

    private fun Ctx.groupsString(): String = groupsString(groups(), groupSeparator)

    /**
     * Interpret `runs.<side>`; `null` when the config declares no such
     * side. [defaultTweakClass] is the side's FML tweaker
     * (`net.minecraftforge.fml.common.launcher.FMLTweaker`/
     * `FMLServerTweaker`) used when `env.tweakClass` is absent.
     */
    fun forSide(
        config: UserdevConfig,
        side: String,
        defaultTweakClass: String,
        ctx: Ctx
    ): RunSpec? {
        val run = config.runs[side] ?: return null
        val rawMain = run["main"]?.jsonPrimitive?.content ?: return null
        val tweakClass = (run["env"] as? JsonObject)?.get("tweakClass")
            ?.jsonPrimitive?.content

        if (rawMain.startsWith("net.minecraftforge.legacydev")) {
            return RunSpec(
                mainClass = "net.minecraft.launchwrapper.Launch",
                args = listOf("--tweakClass", tweakClass ?: defaultTweakClass),
                jvmArgs = listOf(
                    "-Dfml.deobfuscatedEnvironment=true",
                    "-Dfml.ignoreInvalidMinecraftCertificates=true",
                    "-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=${ctx.srgToMcpAsSrg()}"
                ),
                environment = mapOf("MOD_CLASSES" to ctx.groupsString()),
                tweakClass = tweakClass,
                legacyDev = true,
            )
        }

        val args = UserdevConfig.flattenArgs(run["args"])
            .map(ctx::argValue)
        val jvmArgs = mutableListOf<String>()
        jvmArgs += UserdevConfig.flattenArgs(run["jvmArgs"])
            .map(ctx::argValue)
        jvmArgs += (run["props"] as? JsonObject)?.entries?.mapNotNull { (k, v) ->
            UserdevConfig.flattenArgs(v).singleOrNull()
                ?.let { "-D$k=${ctx.argValue(it)}" }
        } ?: emptyList()

        val environment = buildMap {
            config.json["spec"]?.jsonPrimitive?.content?.let {
                put("FORGE_SPEC", it)
            }
            (run["env"] as? JsonObject)?.entries?.forEach { (k, v) ->
                UserdevConfig.flattenArgs(v).singleOrNull()
                    ?.let { put(k, ctx.argValue(it)) }
            }
            putIfAbsent("MOD_CLASSES", ctx.groupsString())
        }

        // forge >=49: `main` is already BootstrapDevMain — forge's
        // own dev relauncher; no union-relauncher wrap (1.x's
        // useUnionRelaunch predates bootstrap-dev, cleanroom never
        // qualified for it either)
        return RunSpec(
            mainClass = rawMain,
            args = args,
            jvmArgs = jvmArgs,
            environment = environment,
            tweakClass = tweakClass,
            legacyDev = false,
        )
    }
}
