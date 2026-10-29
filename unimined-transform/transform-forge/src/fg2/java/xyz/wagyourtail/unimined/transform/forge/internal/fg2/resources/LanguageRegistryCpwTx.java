package xyz.wagyourtail.unimined.transform.forge.internal.fg2.resources;

import net.lenni0451.classtransform.annotations.*;
import net.lenni0451.classtransform.annotations.injection.CInject;
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2;

import java.io.File;

/**
 * 1.x {@code FixFG2ResourceLoading} (LanguageRegistry, cpw.mods era).
 *
 * <p><b>Why:</b> {@code loadLanguagesFor} calls
 * {@code searchDirForLanguages} once, on the mod's jar file — in dev
 * there is no jar, so lang files under the mod's MOD_CLASSES dirs are
 * never found and languages silently don't load. The inject re-runs
 * the same dir scan over each MOD_CLASSES resource dir.
 */
@CTransformer(name = "cpw.mods.fml.common.registry.LanguageRegistry")
public class LanguageRegistryCpwTx {

    @CStub("cpw/mods/fml/common/ModContainer.getSource()Ljava/io/File;")
    private static native File getSource(Object modContainer);

    // declared with Object for the era-typed Side param; CShadow's
    // combi resolves the real member and remaps the declared desc
    @CShadow("searchDirForLanguages(Ljava/io/File;Ljava/lang/String;Lcpw/mods/fml/relauncher/Side;)V")
    private void searchDirForLanguages(File dir, String pattern, Object side) {
    }

    @CInline
    @CInject(method = "loadLanguagesFor*", target = @CTarget(value = "INVOKE",
            target = "Lcpw/mods/fml/common/registry/LanguageRegistry;searchDirForLanguages(Ljava/io/File;Ljava/lang/String;Lcpw/mods/fml/relauncher/Side;)V", optional = true))
    private void scanExtraDirs(Object modContainer, Object side) {
        for (File dir : UniminedFg2.getDirectories(getSource(modContainer))) {
            searchDirForLanguages(dir, "", side);
        }
    }
}
