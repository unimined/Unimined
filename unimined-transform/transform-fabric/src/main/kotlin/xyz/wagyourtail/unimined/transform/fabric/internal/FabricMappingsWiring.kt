package xyz.wagyourtail.unimined.transform.fabric.internal

import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import java.nio.file.Path

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
    val devCol = devNamespace?.let { d ->
        tree.namespaces.firstOrNull { it.name == d || it.name.startsWith(d) }
    } ?: tree.namespaces.lastOrNull()
    val prodCol = tree.namespaces.firstOrNull {
        it.name == prodNamespace || it.name.startsWith(prodNamespace)
    }
    return xyz.wagyourtail.unimined.transform.internal
        .RuntimeMappingExports.export(
            tree,
            xyz.wagyourtail.unimined.transform.internal.RuntimeMappingExports
                .Spec(
                    format = xyz.wagyourtail.unimined.transform.internal
                        .RuntimeMappingExports.Format.TINY_JAR,
                    emitAll = true,
                    nsRenamer = { ns ->
                        when {
                            ns.name == "official" -> Namespace("official")
                            ns.name == "official:client" ->
                                Namespace("clientOfficial")
                            ns.name == "official:server" ->
                                Namespace("serverOfficial")
                            ns == devCol -> Namespace("named")
                            ns == prodCol -> Namespace("intermediary")
                            else -> ns
                        }
                    },
                    renamerKey = "fabric-loader:dev=$devNamespace," +
                            "prod=$prodNamespace",
                    fileName = "mappings-$environmentId.jar",
                    salt = environmentId
                ),
            cacheDir
        )
}
