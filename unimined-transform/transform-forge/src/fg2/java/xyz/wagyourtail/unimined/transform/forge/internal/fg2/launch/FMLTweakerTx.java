package xyz.wagyourtail.unimined.transform.forge.internal.fg2.launch;

import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2;

/**
 * {@code FMLTweaker.<init>} — preload {@code java.lang.ClassLoader$1}
 * before {@code FMLSecurityManager} installs.
 *
 * <p>JDK8's {@code ClassLoader.checkPackageAccess} lazily
 * instantiates {@code java.lang.ClassLoader$1} on the first packaged
 * {@code defineClass} after a {@code SecurityManager} is installed;
 * that inner-class load re-enters the app loader ({@code File.exists}
 * → {@code checkRead}) while the class is mid-definition →
 * {@code ClassCircularityError: java/lang/ClassLoader$1} (observed on
 * 1.12.2 in {@code FMLTweaker.acceptOptions} at the first post-SM
 * {@code loadClass}). The {@code cpw} (1.7.x) variant installs the
 * manager the same way, so both are patched. The preload itself lives
 * in {@link UniminedFg2#preloadSmRaceGuard()} — spliced into the jar.
 *
 * <p>{@code optional}: era variants without the {@code
 * setSecurityManager} call no-op rather than corrupting.
 */
@CTransformer(name = {
        "cpw.mods.fml.common.launcher.FMLTweaker",
        "net.minecraftforge.fml.common.launcher.FMLTweaker",
})
public class FMLTweakerTx {

    /**
     * Runs immediately before {@code System.setSecurityManager}. This
     * is also ahead of {@code MixinBootstrap.init} in the constructor,
     * making it the earliest point at which the launch loader itself is
     * available for MOD_CLASSES URL injection.
     */
    @CInline
    @CInject(
            method = "<init>",
            target = @CTarget(value = "INVOKE",
                    target = "Ljava/lang/System;setSecurityManager(Ljava/lang/SecurityManager;)V",
                    shift = CTarget.Shift.BEFORE,
                    optional = true)
    )
    private void preloadSmRaceGuard() {
        UniminedFg2.injectModClassUrlsIntoLaunch();
        UniminedFg2.preloadSmRaceGuard();
    }

    /**
     * Cleanroom removes the security-manager installation but invokes
     * {@code MixinBootstrap.init} directly in the constructor. That
     * call is the hard deadline: config resources and plugin classes
     * must already resolve through the launch loader when it runs.
     */
    @CInline
    @CInject(
            method = "<init>",
            target = @CTarget(value = "INVOKE",
                    target = "Lorg/spongepowered/asm/launch/MixinBootstrap;init()V",
                    shift = CTarget.Shift.BEFORE,
                    optional = true)
    )
    private void injectModClassesBeforeMixinBootstrap() {
        UniminedFg2.injectModClassUrlsIntoLaunch();
    }
}
