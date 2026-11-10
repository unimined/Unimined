package xyz.wagyourtail.unimined.remapper.uml

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.remap.stepVerb

/**
 * Plugin id `xyz.wagyourtail.unimined.remapper.uml` — supplies `remap` (and
 * later `unpick`/enrichment) step implementations backed by
 * `UniminedMappingLibrary`.
 *
 * Step impls self-register via `META-INF/services`; applying this plugin
 * only ensures the host plugin (`unimined-remap`) is present.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply(xyz.wagyourtail.unimined.remap.GradlePlugin::class.java)
        val remap = target.extensions.getByType(
            xyz.wagyourtail.unimined.remap.RemapExtension::class.java
        )
        remap.registerDslVerb("unpick") { spec ->
            spec.stepVerb(UnpickSourceStepSpec::class.java) { step, defs ->
                step.defs.from(*defs.toTypedArray())
            }
        }
        for (verb in listOf(
            "nester" to NesterStepSpec::class.java,
            "raven" to RavenStepSpec::class.java,
            "sparrow" to SparrowStepSpec::class.java,
        )) {
            remap.registerDslVerb(verb.first) { spec ->
                spec.stepVerb(verb.second) { step, files ->
                    step.dataFiles.from(*files.toTypedArray())
                }
            }
        }
        remap.registerDslVerb("condor") { spec ->
            spec.stepVerb(CondorStepSpec::class.java)
        }
        remap.registerDslVerb("preen") { spec ->
            spec.stepVerb(PreenStepSpec::class.java)
        }
    }

}
