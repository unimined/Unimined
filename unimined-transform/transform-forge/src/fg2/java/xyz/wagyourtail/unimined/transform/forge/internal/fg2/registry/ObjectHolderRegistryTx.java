package xyz.wagyourtail.unimined.transform.forge.internal.fg2.registry;

import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2;

/**
 * {@code ObjectHolderRegistry.scanTarget} — translates the srg
 * class-name probe before {@code Class.forName} (FG2 era,
 * 1.7.10–1.12.2).
 *
 * <p>{@code findObjectHolders} hardcodes srg literals
 * ({@code net.minecraft.init.Blocks}, {@code Items},
 * {@code MobEffects}, {@code Biomes}, {@code Enchantments},
 * {@code SoundEvents}, {@code PotionTypes}) — correct under the
 * FG2-dev invariant where the runtime jar is srg-named, but a
 * unimined dev jar is dev-namespace-named and the JVM enforces
 * name-equality on {@code forName} results, so the probe string
 * must be translated first. {@link UniminedFg2#probeClassForName}
 * runs the srg->dev map fed to {@code FMLDeobfuscatingRemapper}
 * and then loads; names already dev-shaped pass through.
 *
 * <p>Era split: FG2-era {@code scanTarget} is an <i>instance</i>
 * method with signature {@code (Map,Map,String,String,String,Z,Z)},
 * while 1.13+'s is {@code static} and takes an {@code asm.Type} —
 * classtransform requires the handler's staticness to match the
 * enclosing method, so the two eras need separate transformers
 * ({@link ObjectHolderRegistryModLauncherTx}); a missed signature
 * throws {@code MethodNotFoundException} which aborts every handler
 * in the class, so they can't share one. On the wrong era this
 * transformer's combi misses and it no-ops (log-only under
 * {@code FailStrategy.CONTINUE}).
 */
@CTransformer(name = {
        "net.minecraftforge.registries.ObjectHolderRegistry",
        "cpw.mods.fml.common.registry.ObjectHolderRegistry",
})
public class ObjectHolderRegistryTx {

    // FG2 era (1.7.10–1.12.2): scanTarget is an *instance* method.
    @CInline
    @CRedirect(method = "scanTarget(Ljava/util/Map;Ljava/util/Map;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZ)V",
            target = @CTarget(value = "INVOKE",
            target = "Ljava/lang/Class;forName(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
            optional = true))
    private Class<?> probeForName(String name, boolean init,
                                  ClassLoader loader)
            throws ClassNotFoundException {
        return UniminedFg2.probeClassForName(name, init, loader);
    }
}
