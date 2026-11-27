package xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt;

import net.minecraft.launchwrapper.IClassNameTransformer;
import net.minecraft.launchwrapper.IClassTransformer;

import java.lang.reflect.Method;

/**
 * Dev-launch class-name translator, registered in the
 * {@code DeobfuscationTransformer} slot of
 * {@code FMLDeobfTweaker.injectIntoClassLoader} (see
 * {@link UniminedFg2#deobfTransformerNameOrNull}).
 *
 * <p><b>Why:</b> a unimined dev jar is dev-namespace-named
 * (feather), but FG2-era FML probes carry srg names —
 * {@code ObjectHolderRegistry.scanTarget}'s
 * {@code Class.forName("net.minecraft.init.Blocks")} and friends.
 * {@code LaunchClassLoader.findClass} resolves the probe's resource
 * via {@code untransformName} and defines under
 * {@code transformName}, so both must yield the dev name. Fed
 * {@code CL: srg dev} makes {@code map} srg->dev — matching the
 * remapper's member-map keying ({@code rawFieldMaps} are srg-keyed,
 * so {@code mapFieldName}/{@code mapMethodName} resolve srg probes
 * to dev names the same way {@code ObfuscationReflectionHelper}
 * expects).
 *
 * <p><b>Collision guard:</b> a probe that already IS a dev name can
 * still hit an srg key — feather's
 * {@code net/minecraft/potion/Potion} is srg's
 * {@code Potion -> StatusEffect}. Re-mapping it loads the wrong
 * class entirely. The jar's own entries are the dev-namespace
 * ground truth, so a probe whose {@code .class} resource exists on
 * the classpath passes through untranslated; only genuine misses
 * (srg probes) go through the map.
 *
 * <p><b>Self-contained on purpose:</b> {@code transformName} runs
 * inside {@code findClass} — including the load that defines THIS
 * transformer and any helper it might call. Reaching into
 * {@code UniminedFg2} from here re-enters the loader mid-definition
 * ({@code ClassCircularityError}), so the reflective resolution is
 * inlined and re-entrance-guarded.
 */
public class UniminedFg2NameTransformer implements IClassTransformer, IClassNameTransformer {

    private static volatile Object remapper;          // FMLDeobfuscatingRemapper.INSTANCE
    private static volatile Method mapMethod;         // map(String) — src(srg) -> dst(dev)
    private static volatile ClassLoader launchLoader; // Launch.classLoader (LCL)
    private static volatile boolean resolved;

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        return basicClass;
    }

    @Override
    public String unmapClassName(String name) {
        return translate(name);
    }

    @Override
    public String remapClassName(String name) {
        return translate(name);
    }

    private static String translate(String name) {
        if (!resolve()) return name;
        try {
            String internal = name.replace('.', '/');
            if (launchLoader.getResource(internal + ".class") != null) {
                return name;
            }
            return ((String) mapMethod.invoke(remapper, internal))
                    .replace('/', '.');
        } catch (Throwable ignored) {
            return name;
        }
    }

    /**
     * {@code FMLDeobfuscatingRemapper.INSTANCE} resolved through
     * {@code Launch.classLoader} — the remapper is a transformer
     * exclusion, so the game's copy is LCL-defined and binding via
     * our own loader would hit the detached AppClassLoader copy.
     * Re-entrance safe: {@code resolved} flips before the class
     * loads this method itself triggers.
     */
    private static boolean resolve() {
        if (resolved) return remapper != null;
        resolved = true;
        try {
            launchLoader = (ClassLoader) Class
                    .forName("net.minecraft.launchwrapper.Launch")
                    .getField("classLoader").get(null);
            for (String cls : new String[]{
                    "net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper",
                    "cpw.mods.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper"}) {
                try {
                    Class<?> c = launchLoader.loadClass(cls);
                    remapper = c.getField("INSTANCE").get(null);
                    mapMethod = c.getMethod("map", String.class);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
