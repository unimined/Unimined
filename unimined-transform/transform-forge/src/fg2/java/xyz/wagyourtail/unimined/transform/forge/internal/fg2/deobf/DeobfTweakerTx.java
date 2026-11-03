package xyz.wagyourtail.unimined.transform.forge.internal.fg2.deobf;

import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CModifyConstant;
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2;

/**
 * 1.x {@code FixFG2DeobfEnvironment}: strip FML's runtime
 * {@code DeobfuscationTransformer} from {@code injectIntoClassLoader}.
 *
 * <p><b>Why:</b> a unimined dev jar is already deobfuscated — FML's
 * runtime deobf transformer would double-remap and break the run.
 * Rather than 1.x's hardcoded LDC swap to {@code null}, the constant
 * is passed through {@link UniminedFg2#deobfTransformerNameOrNull} at
 * call time: {@code null} by default, the original name when
 * {@code unimined.fg2.runtimeDeobf} is set — flippable without
 * regenerating the jar.
 */
@CTransformer(name = {
        "net.minecraftforge.fml.common.launcher.FMLDeobfTweaker",
        "cpw.mods.fml.common.launcher.FMLDeobfTweaker",
})
public class DeobfTweakerTx {

    // `injectIntoClassLoader` has a dozen String LDCs — the exact
    // transformer-name constant must be targeted (an untargeted
    // @CModifyConstant requires exactly one constant). `optional`
    // since each class variant only carries its own era's constant.
    @CInline
    @CModifyConstant(
            method = "injectIntoClassLoader*",
            stringValue = "net.minecraftforge.fml.common.asm.transformers.DeobfuscationTransformer",
            optional = true
    )
    private String wrapNetForgeTransformerName(String name) {
        return UniminedFg2.deobfTransformerNameOrNull(name);
    }

    @CInline
    @CModifyConstant(
            method = "injectIntoClassLoader*",
            stringValue = "cpw.mods.fml.common.asm.transformers.DeobfuscationTransformer",
            optional = true
    )
    private String wrapCpwTransformerName(String name) {
        return UniminedFg2.deobfTransformerNameOrNull(name);
    }
}
