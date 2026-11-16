package xyz.wagyourtail.unimined.remap.internal

import java.nio.file.Path
import java.util.jar.Manifest
import java.util.zip.ZipFile

/**
 * Cheap per-artifact "is this actually a mod" scan — jars carrying no
 * loader marker and no reference into the mapped package space pass the
 * remap pipeline through unchanged (plain Java libraries on a `mod*`
 * view don't reference mapped classes; running the full pipeline on
 * them is ~50s of analysis per artifact for nothing).
 *
 * Markers are prefixed matcher strings, so the list is user-replaceable
 * without code:
 * - `file:<path>` — jar contains an entry with this exact name
 * - `suffix:<sfx>` — any entry name ends with it
 * - `manifest:<attr>` — `META-INF/MANIFEST.MF` main attributes contain it
 * - `refs:<bytes>` — any entry's raw bytes contain the ASCII string
 *   (catches unmarked libraries that still reference mapped classes —
 *   only works for name-bearing namespaces; obfuscated names are
 *   undetectable)
 *
 * The scan also reads the loom-built-jar self-description contract
 * ([ModJarInfo]) — loom stamps remap directives into dep-jar manifests
 * at build time, and honoring them keeps loom-published mods behaving
 * the same under our pipeline.
 */
object ModMarkerScan {

    /** Default mod indicators across the loader families Unimined
     *  supports: fabric/quilt/neoforge/forge-FML markers, legacy-FML
     *  (FG1/2-era manifest coremods), mixin/AT/AW resources, plus a
     *  `net/minecraft/` reference scan for unmarked MC-adjacent libs. */
    val DEFAULT_MARKERS = listOf(
        "file:fabric.mod.json",
        "file:quilt.mod.json",
        "file:mod.json",
        "file:mcmod.info",
        "file:litemod.json",
        "file:plugin.yml",
        "file:paper-plugin.yml",
        "file:bungee.yml",
        "file:META-INF/mods.toml",
        "file:META-INF/neoforge.mods.toml",
        "file:META-INF/fml.toml",
        "file:mods.toml",
        "file:META-INF/accesstransformer.cfg",
        "file:META-INF/accesswidener",
        "suffix:.mixins.json",
        "suffix:.accesswidener",
        "manifest:FMLCorePlugin",
        "manifest:FMLCorePluginContainsFMLMod",
        "manifest:TweakClass",
        "manifest:MixinConfigs",
        "manifest:FMLModType",
        "manifest:ContainsTweaker",
        "refs:net/minecraft/",
    )

    /**
     * Everything [scan] learned about one jar in a single pass: the
     * marker verdict plus the loom dep-jar manifest contract.
     *
     * @param isMod true when the jar matched a [markers] entry.
     * @param loomRemap `Fabric-Loom-Remap` — loom's per-jar remap
     *  directive: `true` forces remapping even without a marker
     *  (unmarked jars that still reference mapped classes), `false`
     *  opts out even when markers match (loom's
     *  `ArtifactMetadata.RemapRequirements` semantics). `null` = the
     *  attribute is absent; the marker verdict decides.
     * @param knownIndyBsms `Fabric-Loom-Known-Indy-BSMS` —
     *  comma-separated bootstrap-method owner internal names whose
     *  invokedynamic callsite names carry remappable member names
     *  (loom feeds the same set to tiny-remapper's `knownIndyBsm`).
     * @param mixinRemapType `Fabric-Loom-Mixin-Remap-Type` — loom's
     *  refmap handling hint verbatim (e.g. `STATIC`), kept for
     *  pipeline consumers that distinguish refmap modes.
     * @param namespaceHint `Fabric-Mapping-Namespace` — the namespace
     *  loom stamped the jar's classes as, when declared.
     */
    data class ModJarInfo(
        val isMod: Boolean,
        val loomRemap: Boolean?,
        val knownIndyBsms: List<String>,
        val mixinRemapType: String?,
        val namespaceHint: String?,
    )

    /** True when [jar] matches any marker in [markers]. */
    fun isModJar(jar: Path, markers: List<String>): Boolean =
        scan(jar, markers).isMod

    /**
     * Single-pass jar scan: marker verdict + loom manifest contract.
     * Opens the zip once — the manifest attributes are read while the
     * entry-name checks run, and the `refs:` byte scan only runs when
     * the cheaper checks haven't already decided.
     */
    fun scan(jar: Path, markers: List<String>): ModJarInfo {
        val files = HashSet<String>()
        val suffixes = mutableListOf<String>()
        val manifestAttrs = mutableListOf<String>()
        val refs = mutableListOf<ByteArray>()
        for (m in markers) {
            val i = m.indexOf(':')
            if (i < 0) continue
            when (m.substring(0, i)) {
                "file" -> files += m.substring(i + 1)
                "suffix" -> suffixes += m.substring(i + 1)
                "manifest" -> manifestAttrs += m.substring(i + 1)
                "refs" -> refs += m.substring(i + 1).toByteArray(Charsets.ISO_8859_1)
            }
        }
        var isMod = false
        var loomRemap: Boolean? = null
        var knownIndyBsms = emptyList<String>()
        var mixinRemapType: String? = null
        var namespaceHint: String? = null
        ZipFile(jar.toFile()).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            val names = entries.mapTo(HashSet()) { it.name }
            val manifest = if ("META-INF/MANIFEST.MF" in names) {
                zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF"))
                    .use { Manifest(it).mainAttributes }
            } else null
            if (manifest != null) {
                manifest.getValue("Fabric-Loom-Remap")
                    ?.let { loomRemap = it.toBoolean() }
                manifest.getValue("Fabric-Loom-Known-Indy-BSMS")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.let { knownIndyBsms = it }
                mixinRemapType = manifest.getValue("Fabric-Loom-Mixin-Remap-Type")
                namespaceHint = manifest.getValue("Fabric-Mapping-Namespace")
            }
            if (files.isNotEmpty() || suffixes.isNotEmpty() || manifestAttrs.isNotEmpty()) {
                if (names.any { it in files }) isMod = true
                else if (names.any { n -> suffixes.any { n.endsWith(it) } }) isMod = true
                else if (manifest != null && manifestAttrs.isNotEmpty()) {
                    if (manifest.keys.any { it.toString() in manifestAttrs }) isMod = true
                }
            }
            if (!isMod && refs.isNotEmpty()) {
                for (e in entries) {
                    val bytes = zip.getInputStream(e).readBytes()
                    if (refs.any { bytes.containsBytes(it) }) {
                        isMod = true
                        break
                    }
                }
            }
        }
        return ModJarInfo(isMod, loomRemap, knownIndyBsms, mixinRemapType, namespaceHint)
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty() || size < needle.size) return false
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

}
