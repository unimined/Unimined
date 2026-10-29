package xyz.wagyourtail.unimined.transform.forge.internal.fg2.resources;

import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2;

import java.io.File;

/**
 * 1.x {@code FixFG2ResourceLoading} (DirectoryDiscoverer).
 *
 * <p><b>Why:</b> {@code exploreFileSystem} resolves
 * {@code mcmod.info} as {@code new File(modSource, "mcmod.info")} —
 * in dev the mod "source" is a classes dir that doesn't contain the
 * file; it lives in the sibling resources dir. The redirect sends the
 * lookup through {@link UniminedFg2#findMcModInfo}, which checks the
 * mod's MOD_CLASSES roots first and falls back to the mod source.
 */
@CTransformer(name = {
        "cpw.mods.fml.common.discovery.DirectoryDiscoverer",
        "net.minecraftforge.fml.common.discovery.DirectoryDiscoverer",
})
public class DirectoryDiscovererTx {

    @CInline
    @CRedirect(method = "exploreFileSystem*", target = @CTarget(value = "NEW",
            target = "java/io/File", ordinal = 0, optional = true))
    private File findMcModInfo(File parent, String child) {
        return UniminedFg2.findMcModInfo(parent, child);
    }
}
