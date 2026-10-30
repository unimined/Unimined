package xyz.wagyourtail.unimined.transform.forge.internal.fg2

import xyz.wagyourtail.unimined.transform.internal.classtransform.ClassTransformJarFix
import java.nio.file.FileSystem
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * FG2-era dev-environment fixes, ported from 1.x's FixFG2* classes.
 *
 * FG2-era FML (1.7.10–1.12.2) assumes everything is a jar on disk:
 * coremods are discovered by scanning jar files, mod resources come
 * from the mod jar, and classpath entries are invisible to the mod
 * loader. In a dev environment the mod classes live as loose
 * directories on the classpath (MOD_CLASSES) and coremods come from
 * project dependencies, so stock FG2 finds nothing. These fixes
 * teach FG2-era classes to look at the classpath too.
 *
 * Driven by classtransform transformers grouped by runtime flag in
 * `fg2.coremods` / `fg2.resources` / `fg2.deobf` — `@CInline`
 * handlers mean the transformed classes never reference the
 * transformer classes or classtransform at runtime; behavior lives
 * in the spliced [xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2].
 * `FolderResourcePackTx` uses a class-level `@CASM` handler for its
 * delegate generation since the overrides carry obf/srg names —
 * resolved from the jar's superclass via the class provider.
 *
 * Every class is `exists`-gated, and each transformer target uses
 * `optional = true` — an unseen variant no-ops rather than corrupting
 * (the original injections were only validated against the latest
 * forge commit per MC range).
 *
 * Runtime kill-switches are read inside `UniminedFg2` so they don't
 * require regenerating the jar:
 * `unimined.fg2.autoCoremods`, `unimined.fg2.modClasses`,
 * `unimined.fg2.runtimeDeobf`.
 */
object FG2DevFixes {

    private const val HELPER_ENTRY =
        "xyz/wagyourtail/unimined/transform/forge/internal/fg2/rt/UniminedFg2.class"

    /** Transformer classes applied per matching jar entry. */
    private val transformers = mapOf(
        "xyz.wagyourtail.unimined.transform.forge.internal.fg2.deobf.DeobfTweakerTx" to listOf(
            "net/minecraftforge/fml/common/launcher/FMLDeobfTweaker.class",
            "cpw/mods/fml/common/launcher/FMLDeobfTweaker.class",
        ),
        "xyz.wagyourtail.unimined.transform.forge.internal.fg2.coremods.CoreModManagerCpwTx" to listOf(
            "cpw/mods/fml/relauncher/CoreModManager.class",
        ),
        "xyz.wagyourtail.unimined.transform.forge.internal.fg2.coremods.CoreModManagerForgeTx" to listOf(
            "net/minecraftforge/fml/relauncher/CoreModManager.class",
        ),
        "xyz.wagyourtail.unimined.transform.forge.internal.fg2.resources.LanguageRegistryCpwTx" to listOf(
            "cpw/mods/fml/common/registry/LanguageRegistry.class",
        ),
        "xyz.wagyourtail.unimined.transform.forge.internal.fg2.resources.LanguageRegistryForgeTx" to listOf(
            "net/minecraftforge/fml/common/registry/LanguageRegistry.class",
        ),
        "xyz.wagyourtail.unimined.transform.forge.internal.fg2.resources.DirectoryDiscovererTx" to listOf(
            "cpw/mods/fml/common/discovery/DirectoryDiscoverer.class",
            "net/minecraftforge/fml/common/discovery/DirectoryDiscoverer.class",
        ),
        "xyz.wagyourtail.unimined.transform.forge.internal.fg2.resources.ITypeDiscovererTx" to listOf(
            "cpw/mods/fml/common/discovery/ITypeDiscoverer.class",
            "net/minecraftforge/fml/common/discovery/ITypeDiscoverer.class",
        ),
        "xyz.wagyourtail.unimined.transform.forge.internal.fg2.resources.FolderResourcePackTx" to listOf(
            "cpw/mods/fml/client/FMLFolderResourcePack.class",
            "net/minecraftforge/fml/client/FMLFolderResourcePack.class",
        ),
    )

    fun fix(fs: FileSystem) =
        ClassTransformJarFix.fix(fs, transformers, listOf(HELPER_ENTRY))

}
