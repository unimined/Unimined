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
 * 1.x {@code FixFG2Coremods} for the cpw.mods era (≤1.7.10 — FML moved
 * to net.minecraftforge.fml at 1.8): classpath coremod discovery in
 * {@code CoreModManager}.
 *
 * <p><b>Why:</b> {@code discoverCoreMods} only sorts files found by
 * scanning jars; classpath entries (the dev {@code classes/} dirs
 * where the coremod under development lives) never reach it. The
 * inject below wraps the candidate list so classpath entries with a
 * manifest {@code FMLCorePlugin} join discovery, and the
 * {@code handleCascadingTweak} prologue gives classpath coremods the
 * same cascading-tweak registration jar coremods get.
 *
 * <ul>
 *   <li>{@code sortFileList} call → {@code sortFileList(addClasspath(files))}
 *       — classpath entries join the sorted coremod candidate list.</li>
 *   <li>{@code gatherLegacyCanidates} result → appended classpath
 *       candidates (the 1.12.x discovery path — the target is
 *       optional in this era).</li>
 *   <li>{@code handleCascadingTweak} head → cascading tweak
 *       registration for classpath coremods.</li>
 * </ul>
 * <p>
 * Behavior is gated at runtime by {@code unimined.fg2.autoCoremods}
 * inside {@link UniminedFg2}.
 */
@CTransformer(name = "cpw.mods.fml.relauncher.CoreModManager")
public class CoreModManagerCpwTx {

    @CShadow
    private static Object tweaker;
    @CShadow
    private static Map<Object, Object> tweakSorting;

    @CStub("cpw/mods/fml/relauncher/FileListHelper.sortFileList([Ljava/io/File;)[Ljava/io/File;")
    private static native File[] sortFileList(File[] files);

    @CStub("cpw/mods/fml/common/launcher/FMLTweaker.injectCascadingTweak(Ljava/lang/String;)V")
    private static native void injectCascadingTweak(Object tweaker, String tweakClassName);

    @CInline
    @CRedirect(method = "discoverCoreMods*", target = @CTarget(value = "INVOKE",
            target = "Lcpw/mods/fml/relauncher/FileListHelper;sortFileList([Ljava/io/File;)[Ljava/io/File;",
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
     * Head of {@code handleCascadingTweak(File, mcModId, tweakClassName,
     * ?, tweakOrder)} — if the file is a classpath entry (jar coremods
     * open it as a JarFile), route it through the tweaker's cascading
     * mechanism and skip the jar path. Signature is stable across the
     * era; era-typed params stay opaque as {@code Object}.
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
