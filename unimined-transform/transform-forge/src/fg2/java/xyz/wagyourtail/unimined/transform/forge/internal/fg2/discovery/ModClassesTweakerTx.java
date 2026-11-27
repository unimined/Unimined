package xyz.wagyourtail.unimined.transform.forge.internal.fg2.discovery;

import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2;

/**
 * MOD_CLASSES classpath injection — the dev-launch half of FG2-era
 * mod discovery.
 *
 * <p><b>Why:</b> FG2-era FML discovers classpath mods by scanning
 * the launch classloader's source list, and mod classes load through
 * it ({@code ModClassLoader.loadClass} delegates). Unimined dev
 * launches deliberately keep mod output dirs off
 * {@code java.class.path} — they ride the {@code MOD_CLASSES} env
 * var, the loom/modlauncher shape — a variable FG2-era FML never
 * consumes (its {@code MOD_CLASSES} reader was ForgeGradle's
 * GradleStart, absent from userdev/foundation chains). The result:
 * nothing about the mod is discoverable or even loadable.
 *
 * <p>The fix replicates the classic FG dev state — every output dir
 * on the classpath — by appending each existing {@code MOD_CLASSES}
 * member to the launch classloader. The primary client-side injection
 * runs in {@code FMLTweaker.<init>} before MixinBootstrap (see
 * {@code FMLTweakerTx}); this {@code injectIntoClassLoader} hook is
 * the idempotent fallback for tweaker chains that do not pass through
 * that constructor and still precedes coremod setup/discovery there.
 * Vanilla per-directory discovery then sees the dirs natively;
 * cleanroom's rewritten pairing sees whatever its grouping can pair,
 * with {@link ModDiscovererTx} mopping up the rest.
 *
 * <p>Targets the client <i>and</i> server tweakers of both package
 * eras, plus the cascading dev tweaker for chains that reach it —
 * the helper is idempotent (foundation dedups {@code addURL};
 * duplicates are otherwise harmless) and a no-op when
 * {@code MOD_CLASSES} is unset (prod launches).
 */
@CTransformer(name = {
        "cpw.mods.fml.common.launcher.FMLTweaker",
        "net.minecraftforge.fml.common.launcher.FMLTweaker",
        "cpw.mods.fml.common.launcher.FMLServerTweaker",
        "net.minecraftforge.fml.common.launcher.FMLServerTweaker",
        "cpw.mods.fml.common.launcher.FMLDeobfTweaker",
        "net.minecraftforge.fml.common.launcher.FMLDeobfTweaker",
})
public class ModClassesTweakerTx {

    /**
     * Runs before any tweaker body — the classloader parameter is the
     * launch loader itself. {@code injectIntoClassLoader} is the
     * {@code ITweaker} interface method, stable across both package
     * eras; the wildcard covers signature-suffix variants.
     */
    @CInline
    @CInject(method = "injectIntoClassLoader*", target = @CTarget("HEAD"))
    private void injectModClasses(Object classLoader) {
        UniminedFg2.injectModClassUrls(classLoader);
    }
}
