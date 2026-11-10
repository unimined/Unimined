package xyz.wagyourtail.unimined.transform.fabric.internal

import xyz.wagyourtail.unimined.api.util.writeAtomically
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.tiny.v2.TinyV2Writer
import xyz.wagyourtail.unimined.mapping.sink.emit
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists

/**
 * `mappings/mappings.tiny` for the loader's `MappingConfiguration` —
 * fabric-family runtimes only accept canonical namespace names
 * (`official`, `clientOfficial`/`serverOfficial`, `intermediary`,
 * `named`), so the merged tree is re-emitted with the environment's
 * internal column names relabeled: prod → `intermediary`, dev →
 * `named`, `official:*` → `<side>Official`. On the runtime classpath
 * this drives `getRuntimeNamespace()` (`named` in dev, `intermediary`
 * in prod), the classTweaker header check, and the loader's
 * `MappingResolver` — mirroring `exportUnprotectMappings` in
 * `transform-forge`.
 *
 * Written only when the emitted content differs so the file timestamp
 * stays stable across identical resolutions.
 */
internal fun exportLoaderMappings(
    tree: MappingTree,
    devNamespace: String?,
    prodNamespace: String,
    environmentId: String,
    cacheDir: Path
): Path {
    val file = cacheDir.resolve("mappings-$environmentId.jar")
    val devCol = devNamespace?.let { d ->
        tree.namespaces.firstOrNull { it.name == d || it.name.startsWith(d) }
    } ?: tree.namespaces.lastOrNull()
    val prodCol = tree.namespaces.firstOrNull {
        it.name == prodNamespace || it.name.startsWith(prodNamespace)
    }
    val tiny = buildString {
        tree.emit(
            TinyV2Writer.write(::append),
            nsRenamer = { ns ->
                when {
                    ns.name == "official" -> Namespace("official")
                    ns.name == "official:client" -> Namespace("clientOfficial")
                    ns.name == "official:server" -> Namespace("serverOfficial")
                    ns == devCol -> Namespace("named")
                    ns == prodCol -> Namespace("intermediary")
                    else -> ns
                }
            }
        )
    }
    if (!file.exists() ||
        file.toFile().readBytes().toString(Charsets.UTF_8) != tiny
    ) {
        Files.createDirectories(cacheDir)
        file.writeAtomically { tmp ->
            ZipOutputStream(Files.newOutputStream(tmp).buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("mappings/mappings.tiny"))
                zip.write(tiny.toByteArray())
                zip.closeEntry()
            }
        }
    }
    return file
}
