package xyz.wagyourtail.unimined.transform.forge.internal.fg2.deobf;

import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CReplaceCallback;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.objectweb.asm.ClassReader;
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2;

import java.io.IOException;

/**
 * {@code FMLDeobfuscatingRemapper.findAndMergeSuperMaps} superclass
 * resolution fix for unimined dev jars.
 *
 * <p><b>Why:</b> a unimined dev jar is named entirely on the
 * deobfuscated (feather/mcp) side, but {@code findAndMergeSuperMaps}
 * resolves superclasses through {@code
 * ClassPatchManager.getPatchedResource(name, map(name), classLoader)},
 * which reads class bytes under the <i>srg</i> name — those entries
 * don't exist in a feather-named jar, so the lookup returns null and
 * inherited members silently never merge into the field/method maps
 * (e.g. {@code mapFieldName} misses for members declared on a
 * superclass — {@code AlphaWorldStorage.field_75771_c} is declared on
 * {@code SaveHandler}). And when bytes ARE found, {@code
 * ClassReader.getSuperName()}/{@code getInterfaces()} come back in the
 * jar's naming while {@code rawFieldMaps}/{@code rawMethodMaps} are
 * keyed on srg names, so the merge keys miss again.
 *
 * <p><b>What:</b> the method body is replaced outright (head inject +
 * cancel) with the same flow minus the two naming mismatches: bytes
 * are read under the srg name first and {@code map(name)} (the name
 * the dev jar actually carries) second; the resulting super/interface
 * names are {@code unmap}ped back to srg before {@code mergeSuperMaps}.
 * The {@code ClassPatchManager} hop is dropped entirely — its only
 * value is binpatch application, which a dev jar has already had
 * applied. Under {@code unimined.fg2.runtimeDeobf} (srg-runtime
 * launches whose jars ARE srg-named) the inject returns without
 * cancelling and FML's original body runs untouched.
 */
@CReplaceCallback
@CTransformer(name = {
        "cpw.mods.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper",
        "net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper",
})
public class DeobfRemapperTx {

    /** {@code private LaunchClassLoader classLoader} — both eras. */
    @CShadow
    private LaunchClassLoader classLoader;

    /** {@code public String map(String)} — srg → runtime name. */
    @CShadow
    public native String map(String name);

    /** {@code public String unmap(String)} — runtime → srg name. */
    @CShadow
    public native String unmap(String name);

    /** {@code mergeSuperMaps(String, String, String[])} — both eras. */
    @CShadow
    public native void mergeSuperMaps(String name, String superName, String[] interfaces);

    /**
     * Head replacement for {@code findAndMergeSuperMaps(String)} —
     * mirrors FML's body with the byte-source and merge-key naming
     * fixes described above, then cancels. Cancellation is
     * unconditional (mirroring FML's {@code IOException} catch); the
     * runtime-deobf early return is the only path that preserves the
     * stock body.
     */
    @CInline
    @CInject(method = "findAndMergeSuperMaps*",
            target = @CTarget("HEAD"), cancellable = true)
    private void devSuperMaps(String name, InjectionCallback callback) {
        if (System.getProperty(UniminedFg2.PROP_RUNTIME_DEOBF) != null) return;
        try {
            byte[] bytes = classLoader.getClassBytes(name);
            if (bytes == null) {
                String mapped = map(name);
                if (!mapped.equals(name)) {
                    bytes = classLoader.getClassBytes(mapped);
                }
            }
            String superName = null;
            String[] interfaces = new String[0];
            if (bytes != null) {
                ClassReader reader = new ClassReader(bytes);
                superName = reader.getSuperName();
                if (superName != null) superName = unmap(superName);
                interfaces = reader.getInterfaces();
                for (int i = 0; i < interfaces.length; i++) {
                    interfaces[i] = unmap(interfaces[i]);
                }
            }
            mergeSuperMaps(name, superName, interfaces);
        } catch (IOException e) {
            e.printStackTrace();
        }
        callback.setCancelled(true);
    }
}
