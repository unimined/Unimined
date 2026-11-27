package xyz.wagyourtail.unimined.transform.forge.internal.fg2.deobf;

import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CReplaceCallback;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.launchwrapper.Launch;
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2;

import java.lang.reflect.Method;

/**
 * {@code DeobfuscationTransformer.unmapClassName} dev-namespace
 * collision guard.
 *
 * <p><b>Why:</b> a unimined dev jar is dev-namespace-named on disk,
 * but {@code unmapClassName} inverse-maps through the fed
 * srg&rarr;dev table: a dev name IS a value in that table, so the
 * inverse lookup rewrites it back to the srg name and
 * {@code LaunchClassLoader.findClass} then probes a {@code .class}
 * resource path that does not exist — every dev-named class load
 * ends in {@code ClassNotFoundException} ({@code
 * net.minecraft.util.crash.CrashReportDetail} is merely the first).
 * FG2-era FML never hits this because the dev environments it was
 * written for carry srg==mcp class names; feather-style mappings
 * rename classes into different packages, exposing the inverse hit.
 *
 * <p>LexForge-era dev launches dodge the stock transformer entirely
 * — {@link DeobfTweakerTx} substitutes {@code UniminedFg2NameTransformer}
 * into the string-based {@code registerTransformer} call — but
 * Cleanroom's {@code FMLDeobfTweaker} instantiates {@code new
 * DeobfuscationTransformer()} directly and hands the instance to
 * foundation's {@code TransformerDelegate.registerRenameTransformer},
 * so no string constant exists to substitute and the stock
 * transformer survives with its inverse-mapping intact.
 *
 * <p><b>What:</b> the resource path IS the dev jar's ground truth, so
 * a name whose {@code .class} entry exists on the classpath passes
 * through untranslated; only genuine misses (srg-authored probes
 * such as {@code ObjectHolderRegistry.scanTarget}'s hardcoded
 * {@code net.minecraft.init.*} literals) go through the remapper's
 * forward ({@code map}, srg&rarr;dev) lookup. The forward lookup is
 * reflective because the remapper's binary name differs between the
 * cpw (1.7.10) and netforge (1.8+) eras and the fg2 source set
 * deliberately carries no forge compile-time dependency. The miss
 * path is rare and inherently expensive (it ends in a classpath scan
 * or a {@code ClassNotFoundException}), so the resolution is not
 * cached.
 *
 * <p><b>Re-entrance:</b> this runs inside {@code findClass}, so the
 * body only touches classes that are guaranteed loaded before any
 * name translation can happen — {@code Launch} is the launchwrapper
 * bootstrap and the remapper class is necessarily resolved by the
 * transformer's own constructor (which fed it) before registration.
 * No reference to {@code UniminedFg2} or
 * {@code UniminedFg2NameTransformer} may appear here: loading either
 * mid-translation re-enters the loader circularly.
 *
 * <p>Under {@code unimined.fg2.runtimeDeobf} (srg-runtime launches
 * whose jars ARE srg-named) the inject returns without cancelling
 * and the stock inverse mapping runs untouched. In LexForge-era dev
 * the substituted-away transformer never runs at all, so this patch
 * is inert there; it only activates where the stock transformer
 * survives registration — Cleanroom.
 */
@CReplaceCallback
@CTransformer(name = {
        "cpw.mods.fml.common.asm.transformers.DeobfuscationTransformer",
        "net.minecraftforge.fml.common.asm.transformers.DeobfuscationTransformer",
})
public class DeobfTransformerTx {

    /**
     * Head replacement for {@code unmapClassName(String)} — see the
     * class javadoc for the collision-guard semantics. Cancels in
     * every non-runtimeDeobf case: the output is always either the
     * input (resource exists / unmapped) or the forward-translated
     * dev name, matching {@code UniminedFg2NameTransformer}'s
     * translation contract exactly.
     */
    @CInline
    @CInject(method = "unmapClassName*",
            target = @CTarget("HEAD"), cancellable = true)
    private void devUnmap(String name, InjectionCallback callback) {
        if (System.getProperty(UniminedFg2.PROP_RUNTIME_DEOBF) != null) return;
        String internal = name.replace('.', '/');
        String translated = internal;
        if (Launch.classLoader.getResource(internal + ".class") == null) {
            // genuine miss — an srg-authored probe or an absent class:
            // forward-translate (srg -> dev) so probes land on the dev
            // jar's entries; absent classes map to themselves and fail
            // the same way stock behavior would
            try {
                for (String cls : new String[]{
                        "net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper",
                        "cpw.mods.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper"}) {
                    Class<?> remapper;
                    try {
                        remapper = Launch.classLoader.loadClass(cls);
                    } catch (ClassNotFoundException ignored) {
                        continue;
                    }
                    Object inst = remapper.getField("INSTANCE").get(null);
                    Method map = remapper.getMethod("map", String.class);
                    translated = (String) map.invoke(inst, internal);
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        callback.setReturnValue(translated.replace('/', '.'));
        callback.setCancelled(true);
    }
}
