package xyz.wagyourtail.unimined.transform.jarmod.internal.modloader

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.transform.internal.classtransform.ClassTransformJarFix
import java.nio.file.FileSystem
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * ModLoader dev fixes — port of 1.x `ModLoaderPatches` to
 * classtransform annotation handlers, applied via the shared
 * [ClassTransformJarFix] mechanism (same shape as `FG2DevFixes`).
 */
object ModLoaderDevFixes {

    // one transformer covers both variants — every stub self-gates on
    // what the target actually contains (extract skips when
    // readFromClassPath exists, the clinit slice only resolves on the
    // old layout, the init rewrites only resolve on the newer one)
    private val transformers = mapOf(
        "xyz.wagyourtail.unimined.transform.jarmod.internal.modloader.ModLoaderDevFixTx" to listOf(
            "ModLoader.class",
            // post-remap jars move the default-package classes into
            // `net/minecraft/src` (mcp package mapping), so the target
            // has to cover both layouts
            "net/minecraft/src/ModLoader.class",
        ),
    )

    /**
     * 1.x gates: skip when ModLoader.class is absent, is FML's own
     * patched variant (`fmlMarker` field), or FML ships its helper.
     * Risugami's zip ships `ModLoader.class` at jar root; the dev jar's
     * package remap relocates it under `net/minecraft/src`.
     */
    fun fix(fs: FileSystem) {
        val modLoader = listOf(
            fs.getPath("/ModLoader.class"),
            fs.getPath("/net/minecraft/src/ModLoader.class"),
        ).firstOrNull { it.exists() } ?: return
        if (fs.getPath("/cpw/mods/fml/common/modloader/ModLoaderHelper.class").exists()) return
        val cn = ClassNode().also { ClassReader(modLoader.readBytes()).accept(it, 0) }
        if (cn.fields.any { it.name == "fmlMarker" }) return
        ClassTransformJarFix.fix(fs, transformers)
    }
}
