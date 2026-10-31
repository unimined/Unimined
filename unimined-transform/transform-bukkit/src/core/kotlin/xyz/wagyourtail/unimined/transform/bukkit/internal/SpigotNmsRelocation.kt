package xyz.wagyourtail.unimined.transform.bukkit.internal

import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.nio.file.Path

/**
 * Derives the `spigotProd` namespace's versioned NMS package —
 * CraftBukkit/Spigot >=1.17 relocate server classes into
 * `net/minecraft/server/<versionPackage>/`, where [versionPackage]
 * is spigot's `v<maj>_<min>_R<rev>` token (from BuildData
 * `packageVersion`, not derivable from the mc version alone).
 *
 * Mirrors 1.x `spigotProd()` — rewrites each class's `spigotProd`
 * name to `<versioned package>/<simple name>`, keeping member names
 * obfuscated (that's what distinguishes prod from named).
 */
internal class SpigotNmsRelocationProcessor(
    private val versionPackage: () -> String
) : JarProcessor {

    override val name = "spigot-nms-relocation"

    class Spec(val versionPackage: String) : JarProcessor.JarProcessorSpec

    override fun buildSpec(context: JarProcessor.SpecContext) =
        Spec(versionPackage())

    override fun serializeSpec(spec: JarProcessor.JarProcessorSpec) =
        (spec as Spec).versionPackage

    override fun deserializeSpec(payload: String) = Spec(payload)

    override fun processJar(
        jar: Path,
        spec: JarProcessor.JarProcessorSpec,
        context: JarProcessor.ProcessorContext
    ) {
        // mapping-only processor — the jar is already correct
    }

    override fun processMappings() = JarProcessor.MappingsProcessor { data, spec, _ ->
        val tree = data.view(MappingTree::class.java) ?: return@MappingsProcessor data
        val ns = tree.namespaces.firstOrNull { it.name == "spigotProd" }
            ?: return@MappingsProcessor data
        val pkg = "net/minecraft/server/${(spec as Spec).versionPackage}"
        for (cls in tree.classes) {
            val current = cls.names[ns] ?: continue
            (cls.names as MutableMap)[ns] = InternalName.unchecked(
                "$pkg/${current.value.substringAfterLast("/")}"
            )
        }
        data
    }
}
