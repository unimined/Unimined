package xyz.wagyourtail.unimined.transform.forge.internal.fg2.registry;

import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2;

/**
 * {@code ObjectHolderRegistry} fixes for the ModLauncher era
 * (1.13+), where {@code scanTarget} is {@code static} and takes an
 * {@code asm.Type} — see {@link ObjectHolderRegistryTx} for the
 * FG2-era variant and the era-split rationale.
 *
 * <p>Two fixes:
 *
 * <p>1. {@code scanTarget}'s srg class-name probe —
 * {@code Class.forName} gets the same {@code srg->dev} translation
 * as the FG2 era via {@link UniminedFg2#probeClassForName}.
 *
 * <p>2. {@code findObjectHolders} hardcodes the srg package in
 * {@code lambda$findObjectHolders$5}: TYPE-target scans pass
 * {@code extractInjected = className.startsWith("net.minecraft.init")}
 * — vanilla holder classes live in {@code net.minecraft.init} under
 * srg, and that flag is what makes {@code Particles}'s
 * {@code SimpleParticleType} fields (not registry entries) silently
 * skip instead of throwing. A dev-namespace jar scatters the holders
 * ({@code net.minecraft.entity.particle.ParticleTypes} etc.), so the
 * check must widen to the package prefix all vanilla classes share:
 * {@code net.minecraft.} — strictly more correct, and identical under
 * srg where {@code net.minecraft.init} is a subset. The field-target
 * lambda carries no prefix check and needs none.
 */
@CTransformer(name = "net.minecraftforge.registries.ObjectHolderRegistry")
public class ObjectHolderRegistryModLauncherTx {

    // ModLauncher era (1.13+): scanTarget is *static*.
    @CInline
    @CRedirect(method = "scanTarget(Ljava/util/Map;Ljava/util/Map;Lorg/objectweb/asm/Type;Ljava/lang/String;Ljava/lang/String;ZZ)V",
            target = @CTarget(value = "INVOKE",
            target = "Ljava/lang/Class;forName(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
            optional = true))
    private static Class<?> probeForName(String name, boolean init,
                                         ClassLoader loader)
            throws ClassNotFoundException {
        return UniminedFg2.probeClassForName(name, init, loader);
    }

    @CInline
    @CRedirect(method = "lambda$findObjectHolders$*", target = @CTarget(value = "INVOKE",
            target = "Ljava/lang/String;startsWith(Ljava/lang/String;)Z",
            optional = true))
    private static boolean netInitPrefix(String className, String prefix) {
        return className.startsWith("net.minecraft.init".equals(prefix)
                ? "net.minecraft." : prefix);
    }
}
