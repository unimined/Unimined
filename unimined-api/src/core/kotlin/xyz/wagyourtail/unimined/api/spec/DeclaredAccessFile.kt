package xyz.wagyourtail.unimined.api.spec

import java.io.Serializable

/**
 * Per-file escape hatch for the access-conversion pipeline
 * (`MinecraftEnvironment.accessFile(...)`): declares an access file
 * outside the default `accessWideners`/`accessTransformers` collection
 * → canonical merge.
 *
 * @param path absolute path of the source file (resolved at DSL time).
 * @param format explicit source format (`"aw"`, `"at"`, `"legacy_at"`)
 *   — `null` = detect by filename/content.
 * @param output jar path to emit the converted file at — `null` =
 *   the loader's canonical path (e.g. `META-INF/accesstransformer.cfg`,
 *   `{modid}.accesswidener`).
 * @param declare whether loader metadata references the emitted path.
 */
data class DeclaredAccessFile(
    val path: String,
    val format: String? = null,
    val output: String? = null,
    val declare: Boolean = true
) : Serializable
