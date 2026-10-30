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

    /** True when [jar] matches any marker in [markers]. */
    fun isModJar(jar: Path, markers: List<String>): Boolean {
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
        ZipFile(jar.toFile()).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            if (files.isNotEmpty() || suffixes.isNotEmpty() || manifestAttrs.isNotEmpty()) {
                val names = entries.mapTo(HashSet()) { it.name }
                if (names.any { it in files }) return true
                if (names.any { n -> suffixes.any { n.endsWith(it) } }) return true
                if (manifestAttrs.isNotEmpty() && "META-INF/MANIFEST.MF" in names) {
                    val attrs = zip.getInputStream(
                        zip.getEntry("META-INF/MANIFEST.MF")
                    ).use { Manifest(it).mainAttributes }
                    if (attrs.keys.any { it.toString() in manifestAttrs }) return true
                }
            }
            if (refs.isNotEmpty()) {
                for (e in entries) {
                    val bytes = zip.getInputStream(e).readBytes()
                    if (refs.any { bytes.containsBytes(it) }) return true
                }
            }
        }
        return false
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
