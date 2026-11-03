package xyz.wagyourtail.unimined.transform.bukkit.internal

import org.gradle.api.GradleException
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter
import java.io.Serializable

/**
 * `plugin.yml` base emitter shared by the spigot-family loaders —
 * `craftbukkit`/`spigot`/`paper` all consume the same flat key shape
 * (`name`/`main`/`version`/`api-version`/`authors`/custom keys); only
 * the emitted file name and paper's `type`/`loader` keys differ. The
 * plugin `main` class comes from the section's `main` entrypoint;
 * everything else falls back to [xyz.wagyourtail.unimined.mod.LoaderSection.custom]
 * keys (`depend`, `softdepend`, `load`, `commands`, `permissions`, …).
 */
abstract class PluginYamlEmitter : ModMetadataEmitter {

    /** The emitted file name — `plugin.yml` or `paper-plugin.yml`. */
    abstract val fileName: String

    override fun emit(context: ModMetadataEmitter.EmitContext): Map<String, ByteArray> {
        val meta = context.metadata
        val section = meta.sections[loader]
        val main = section?.entrypoints?.get("main")?.firstOrNull()
            ?: throw GradleException(
                "mod { loader(\"$loader\") }: $fileName requires a plugin " +
                        "main class — declare `entrypoint(\"com.example.MyPlugin\")`"
            )

        val top = linkedMapOf<String, Serializable>()
        top["name"] = meta.name ?: meta.id ?: "unnamed"
        top["main"] = main
        top["version"] = meta.version ?: "0.0.0"
        top["api-version"] = context.minecraftVersion
        meta.description?.let { top["description"] = it }
        meta.url?.let { top["website"] = it }
        if (meta.authors.size == 1) top["author"] = meta.authors.single()
        else if (meta.authors.isNotEmpty()) top["authors"] = ArrayList(meta.authors)
        extraTopKeys(context, top)
        section?.custom?.forEach { (k, v) -> top[k] = v }

        val body = Yaml(
            DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            }
        ).dump(top)
        return mapOf(fileName to body.toByteArray())
    }

    /** Loader-specific keys after the shared ones (paper's `type`). */
    protected open fun extraTopKeys(
        context: ModMetadataEmitter.EmitContext,
        top: MutableMap<String, Serializable>
    ) {
    }

}

/** `plugin.yml` for `loader("craftbukkit")` / `loader("spigot")`. */
class CraftBukkitModMetadataEmitter : PluginYamlEmitter() {
    override val loader = "craftbukkit"
    override val fileName = "plugin.yml"
}

class SpigotModMetadataEmitter : PluginYamlEmitter() {
    override val loader = "spigot"
    override val fileName = "plugin.yml"
}

/**
 * `paper-plugin.yml` for `loader("paper")` — same flat shape plus
 * `type: PAPER`; `bootstrapper`/`loader` classes come from their
 * entrypoint kinds when declared.
 */
class PaperModMetadataEmitter : PluginYamlEmitter() {
    override val loader = "paper"
    override val fileName = "paper-plugin.yml"

    override fun extraTopKeys(
        context: ModMetadataEmitter.EmitContext,
        top: MutableMap<String, Serializable>
    ) {
        top["type"] = "PAPER"
        val section = context.metadata.sections[loader]
        section?.entrypoints?.get("bootstrapper")?.firstOrNull()
            ?.let { top["bootstrapper"] = it }
        section?.entrypoints?.get("loader")?.firstOrNull()
            ?.let { top["loader"] = it }
    }

}
