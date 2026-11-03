package xyz.wagyourtail.unimined.transform.forge.internal.fg2.coremods;

import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.*;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.CModifyExpressionValue;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 1.x {@code FixFG2Coremods} for the net.minecraftforge era
 * (1.8–1.12.2 — the net.minecraftforge package era): see
 * {@link CoreModManagerCpwTx} — same injections, forge-package
 * {@code FileListHelper}. This era also runs
 * {@code gatherLegacyCanidates} (the 1.12.x discovery path), which
 * gets the same classpath-candidate append.
 */
@CTransformer(name = "net.minecraftforge.fml.relauncher.CoreModManager")
public class CoreModManagerForgeTx {

    @CShadow
    private static Object tweaker;
    @CShadow
    private static Map<Object, Object> tweakSorting;

    @CStub("net/minecraftforge/fml/relauncher/FileListHelper.sortFileList([Ljava/io/File;)[Ljava/io/File;")
    private static native File[] sortFileList(File[] files);

    @CStub("net/minecraftforge/fml/common/launcher/FMLTweaker.injectCascadingTweak(Ljava/lang/String;)V")
    private static native void injectCascadingTweak(Object tweaker, String tweakClassName);

    @CInline
    @CRedirect(method = "discoverCoreMods*", target = @CTarget(value = "INVOKE",
            target = "Lnet/minecraftforge/fml/relauncher/FileListHelper;sortFileList([Ljava/io/File;)[Ljava/io/File;",
            optional = true))
    private static File[] wrapSortFileList(File[] files) {
        return sortFileList(UniminedFg2.addClasspath(files));
    }

    @CInline
    @CModifyExpressionValue(method = "discoverCoreMods*", target = @CTarget(value = "INVOKE",
            target = "L*LibraryManager;gatherLegacyCanidates*()Ljava/util/List;", optional = true))
    private static List<File> wrapGatherLegacyCandidates(List<File> candidates) {
        candidates.addAll(Arrays.asList(UniminedFg2.addClasspath(new File[0])));
        return candidates;
    }

    /**
     * See {@link CoreModManagerCpwTx#cascadingTweakHead}.
     */
    @CInline
    @CInject(method = "handleCascadingTweak*", target = @CTarget("HEAD"),
            cancellable = true)
    private static void cascadingTweakHead(File tweakFile, Object mcModId,
                                           Object tweakClassName, Object query,
                                           Object tweakOrder,
                                           InjectionCallback callback) {
        if (UniminedFg2.isOnClasspath(tweakFile)) {
            injectCascadingTweak(tweaker, (String) tweakClassName);
            tweakSorting.put(tweakClassName, tweakOrder);
            callback.setCancelled(true);
        }
    }
}
