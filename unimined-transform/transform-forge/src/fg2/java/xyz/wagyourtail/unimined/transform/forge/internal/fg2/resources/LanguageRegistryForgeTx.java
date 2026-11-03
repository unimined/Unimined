package xyz.wagyourtail.unimined.transform.forge.internal.fg2.resources;

import net.lenni0451.classtransform.annotations.*;
import net.lenni0451.classtransform.annotations.injection.CInject;
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2;

import java.io.File;

/**
 * 1.x {@code FixFG2ResourceLoading} (LanguageRegistry, forge-package
 * era): see {@link LanguageRegistryCpwTx} — same injection with
 * net.minecraftforge-era {@code ModContainer}/{@code Side}.
 */
@CTransformer(name = "net.minecraftforge.fml.common.registry.LanguageRegistry")
public class LanguageRegistryForgeTx {

    @CStub("net/minecraftforge/fml/common/ModContainer.getSource()Ljava/io/File;")
    private static native File getSource(Object modContainer);

    // declared with Object for the era-typed Side param; CShadow's
    // combi resolves the real member and remaps the declared desc
    @CShadow("searchDirForLanguages(Ljava/io/File;Ljava/lang/String;Lnet/minecraftforge/fml/relauncher/Side;)V")
    private void searchDirForLanguages(File dir, String pattern, Object side) {
    }

    @CInline
    @CInject(method = "loadLanguagesFor*", target = @CTarget(value = "INVOKE",
            target = "Lnet/minecraftforge/fml/common/registry/LanguageRegistry;searchDirForLanguages(Ljava/io/File;Ljava/lang/String;Lnet/minecraftforge/fml/relauncher/Side;)V", optional = true))
    private void scanExtraDirs(Object modContainer, Object side) {
        for (File dir : UniminedFg2.getDirectories(getSource(modContainer))) {
            searchDirForLanguages(dir, "", side);
        }
    }
}
