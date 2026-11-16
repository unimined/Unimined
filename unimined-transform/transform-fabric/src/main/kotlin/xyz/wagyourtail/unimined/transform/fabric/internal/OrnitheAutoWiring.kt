package xyz.wagyourtail.unimined.transform.fabric.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.api.util.reportProblem
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.mapping.internal.MinecraftVersions
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.DevArtifactOnlyStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepScope
import xyz.wagyourtail.unimined.remapper.uml.CondorStepSpec
import xyz.wagyourtail.unimined.remapper.uml.PreenStepSpec
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Ornithe auto-contributions (Ploceus default-on parity): the
 * `condor` local-variable-table pass, the `preen` bridge-method
 * pass, and the `nests` enricher source on the environment's
 * mapping spec (Ploceus's always-appended `NestsMappingSpec` —
 * the `FillingMappingSpec` tree-fill half is already covered by
 * the composer's `fillMissingNames`).
 *
 * Everything self-gates on the resolved loader — only the
 * descriptor with `id == "ornithe"` qualifies; other
 * fabric-family loaders (fabric/quilt/legacyfabric/babric) get
 * nothing. Opt-outs live on the environment:
 * [MinecraftEnvironment.patchLvts]/`disableLvtPatch()`,
 * [MinecraftEnvironment.applyPreen]/`disablePreen()`,
 * [MinecraftEnvironment.autoNests]/`disableNests()`.
 */
internal object OrnitheAutoWiring {

    /**
     * Gradle property overriding the Ornithe meta base URL —
     * for mirrors, airgapped caches and tests (`file:` URLs work).
     * Falls back to the loader descriptor's meta capability.
     */
    private const val META_PROPERTY = "unimined.ornitheMeta"

    private val WEEKLY_SNAPSHOT = Regex("""^(\d+)w(\d+)[a-z]?$""")

    /** Pre-alpha version ids — classic `c*`, indev `in-`, infdev
     *  `inf-`, pre-classic `rd-` — Ornithe normalizes all of these
     *  at or below `0.32.0`, outside the LVT window. */
    private val PRE_ALPHA = Regex("""^(?:c[\d.]|inf?-|rd-).*""")

    /**
     * Ploceus `shouldPatchLvts` parity — the environment's Minecraft
     * version normalizes inside `(0.32.0, 1.8.2-rc.5)`: alpha, beta
     * and release/snapshot builds before 1.8.2 ship stripped local
     * variable tables, infdev-and-older breaks condor's frame
     * analysis, and 1.8.2+ carries real debug info again.
     *
     * Ornithe's `normalizedVersion` is replicated locally so the
     * gate costs no meta round-trip: `a*`/`b*` map to
     * `1.0.0-alpha.*`/`1.0.0-beta.*` (inside), `c*`/`in-*`/`inf-*`/
     * `rd-*` to `<= 0.32.0` (outside), weekly snapshots to
     * `1.x.0-alpha.YY.WW.l` — 15w31a opens the 1.9 line while
     * 15w14a is the post-1.8.2 april-fools build.
     */
    internal fun shouldPatchLvts(versionId: String): Boolean {
        // `empty-*` pseudo versions resolve the real version's gate
        val id = versionId.substringAfter("empty-")
        if (PRE_ALPHA.matches(id)) return false
        WEEKLY_SNAPSHOT.matchEntire(id)?.let {
            val week = it.groupValues[1].toInt() * 100 +
                    it.groupValues[2].toInt()
            return week < 1531 && week != 1514
        }
        return try {
            MinecraftVersions.compare(id, "1.8.2") < 0
        } catch (e: IllegalStateException) {
            // uncomparable ids (joke/conversion builds) are never
            // inside the window
            false
        }
    }

    /** Register the Ornithe-only contributions — called from
     *  [FabricEnvironmentWiring.wire]; inert on every other loader. */
    fun contribute(project: Project, environment: MinecraftEnvironment) {
        contributeAutoSteps(project, environment)
        contributeAutoNests(project, environment)
    }

    /**
     * The env-artifact passes (Ploceus `addMinecraftJarProcessor`
     * order — LvtProcessor then PreenProcessor): `condor` when
     * `patchLvts` and the version gate hold, `preen` whenever it is
     * enabled. Both scope to [StepScope.ENVIRONMENT_ARTIFACT] so
     * dependency pipelines never see them, and ride
     * [DevArtifactOnlyStepSpec]-marked spec subclasses so the same
     * env pipeline running over `remapJar` skips them too (Ploceus's
     * processors only ever see Minecraft jars). Contributed post-DSL
     * so the loader, version and opt-outs are final.
     */
    private fun contributeAutoSteps(
        project: Project, environment: MinecraftEnvironment
    ) {
        val objects = project.objects
        val environmentSpec = project.extensions
            .getByType(RemapExtension::class.java)
            .sourceSet(environment.environmentId)
        project.afterDslEval {
            if (environment.loaderSpec.orNull?.name
                    ?.let { LoaderRegistry.resolveBuiltin(it) }
                    ?.id != "ornithe"
            ) return@afterDslEval
            if (environment.patchLvts.get() &&
                environment.version.orNull?.let(::shouldPatchLvts) == true
            ) {
                environmentSpec.contributeStep(
                    RemapSurface.JAR,
                    objects.newInstance(DevCondorStepSpec::class.java).apply {
                        force.set(false)
                    },
                    StepScope.ENVIRONMENT_ARTIFACT
                )
            }
            if (environment.applyPreen.get()) {
                environmentSpec.contributeStep(
                    RemapSurface.JAR,
                    objects.newInstance(DevPreenStepSpec::class.java),
                    StepScope.ENVIRONMENT_ARTIFACT
                )
            }
        }
    }

    /**
     * Ploceus `NestsMappingSpec` parity — contributes the latest
     * `net.ornithemc:nests` build published for the environment's
     * Minecraft version + side into the mapping chain, so inner-class
     * metadata grafts through the remap. Skipped when the user
     * declared a `nests` source, disabled auto-nests, ran offline, or
     * the meta index is unreachable — Ploceus's layer stays inert the
     * same way when no nests provider is present.
     */
    private fun contributeAutoNests(
        project: Project, environment: MinecraftEnvironment
    ) {
        environment.mappingsSpec.deferredDefaultSources += {
            val descriptor = environment.loaderSpec.orNull?.name
                ?.let { LoaderRegistry.resolveBuiltin(it) }
                ?.takeIf { it.id == "ornithe" }
            val meta = (project.findProperty(META_PROPERTY) as? String)
                ?: descriptor?.capabilities?.get("libraryOverlayMeta")
            if (descriptor == null || meta == null ||
                !environment.autoNests.get() ||
                project.gradle.startParameter.isOffline ||
                userDeclaredNests()
            ) {
                emptyList()
            } else {
                val index = versionsIndex(project, meta)
                collectDeclared {
                    autoNests { gameVersion, envTag ->
                        latestComponent(index, "nests", gameVersion, envTag)
                    }
                }
            }
        }
    }

    /** A `net.ornithemc:nests:` coordinate already lives in the
     *  declared sources — the explicit `mappings { nests(...) }`
     *  declaration wins over the auto contribution. File-declared
     *  nests can't be detected; pair them with `disableNests()`. */
    private fun MappingsSpecImpl.userDeclaredNests(): Boolean =
        sources.any {
            it is MappingsSpecImpl.Source.Maven &&
                    runCatching { it.notation }
                        .getOrNull()?.startsWith("net.ornithemc:nests:") == true
        }

    // memoized meta index — one fetch per meta base URL per daemon;
    // `MISSING` marks a failed fetch so it isn't retried per source call
    private val indexCache = ConcurrentHashMap<String, JsonObject>()
    private val MISSING = JsonObject(emptyMap())

    /** The meta `/v3/versions` index — null when unreachable
     *  (warns once, then the auto contribution stays inert). */
    private fun versionsIndex(project: Project, meta: String): JsonObject? =
        indexCache.computeIfAbsent(meta) {
            try {
                Json.parseToJsonElement(
                    URI("${meta.trimEnd('/')}/v3/versions").toURL().openStream()
                        .use { it.readBytes().decodeToString() }
                ).jsonObject
            } catch (t: Throwable) {
                project.reportProblem(
                    "fabric/ornithe-meta",
                    "unimined: ornithe meta unreachable — skipping " +
                            "auto-nests contribution",
                    t.message
                )
                MISSING
            }
        }.takeIf { it !== MISSING }

    /**
     * The latest published artifact version of [component]
     * (`"nests"`, `"raven"`, `"sparrow"`) for [gameVersion] —
     * [environment] is the slot's meta environment tag
     * (`"client"`/`"server"`/`"*"`); sided slots fall back to the
     * `"*"` merged artifact when no sided entry exists, mirroring
     * Ploceus's merged-artifact selection on non-split versions.
     * Returns the index's `version` field (e.g.
     * `b1.7.3-client+build.7`), or null when nothing matches.
     */
    internal fun latestComponent(
        index: JsonObject?,
        component: String,
        gameVersion: String,
        environment: String
    ): String? {
        val entries = index?.get(component)?.jsonArray ?: return null
        var bestBuild = -1
        var bestExact = false
        var bestVersion: String? = null
        for (el in entries) {
            val entry = el.jsonObject
            if (entry["gameVersion"]?.jsonPrimitive?.contentOrNull
                != gameVersion
            ) continue
            val env = entry["environment"]?.jsonPrimitive?.contentOrNull
            val exact = env == environment
            // `*` slots take only merged artifacts; sided slots take
            // their side, else the merged artifact
            if (!exact && (environment == "*" || env != "*")) continue
            val build = entry["build"]?.jsonPrimitive?.intOrNull ?: continue
            val version = entry["version"]?.jsonPrimitive?.contentOrNull
                ?: continue
            if (bestVersion == null || (exact && !bestExact) ||
                (exact == bestExact && build > bestBuild)
            ) {
                bestBuild = build
                bestExact = exact
                bestVersion = version
            }
        }
        return bestVersion
    }

}

/**
 * Auto-contributed `condor` spec — [DevArtifactOnlyStepSpec] because
 * Ploceus's processors only ever see Minecraft jars: the same
 * environment pipeline minus this marker would run LVT synthesis over
 * `remapJar`'s production mod jar, mutating shipped bytecode. The
 * base spec type still resolves to `CondorStepType`. */
internal abstract class DevCondorStepSpec : CondorStepSpec(),
    DevArtifactOnlyStepSpec

/** [DevCondorStepSpec] for `preen` — bridge-method repair is an
 *  MC-artifact patch, never something a production mod jar wants. */
internal abstract class DevPreenStepSpec : PreenStepSpec(),
    DevArtifactOnlyStepSpec
