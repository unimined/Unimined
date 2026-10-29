package xyz.wagyourtail.unimined.transform.forge.internal.fg2.resources;

import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2;
import xyz.wagyourtail.unimined.transform.internal.classtransform.CSuperOverride;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * {@code FMLFolderResourcePack} classpath/resource fix — the
 * annotation-driven replacement for what 1.x did as raw ASM
 * (FixFG2ResourceLoading).
 *
 * <p><b>Why:</b> FG2-era folder packs only see the mod's own source
 * dir, so resources living under other MOD_CLASSES roots are
 * invisible. The fix records the resolved dirs at construction and
 * teaches {@code hasResource}/{@code getInputStreamByName}/
 * {@code getResourceDomains} to consult them.
 *
 * <p>The superclass member names are obf/srg (the universal jar is
 * compiled against obfuscated names) and vary per version, so nothing
 * can be written as a fixed member spec — instead the
 * {@code SUPER_SELF} injection target matches same-named
 * {@code super.} calls at apply time, and {@link CSuperOverride}
 * names the generated delegates after whatever the jar's superclass
 * actually declares.
 */
@CTransformer(name = {
        "cpw.mods.fml.client.FMLFolderResourcePack",
        "net.minecraftforge.fml.client.FMLFolderResourcePack",
})
public class FolderResourcePackTx {

    /**
     * Resource roots for this pack — populated in the ctor tail,
     * consulted by the injected delegates. Unannotated members of a
     * {@link CTransformer} are copied into the target.
     */
    private File[] unimined$dirs;

    @CInline
    @CInject(method = "<init>", target = @CTarget("RETURN"))
    private void initDirs(Object container) {
        this.unimined$dirs = UniminedFg2.getDirectories(
                UniminedFg2.getContainerSource(container));
    }

    /**
     * {@code super.hasResource(arg)} → dirs lookup. Matched via the
     * {@code SUPER_SELF} target (same-named INVOKESPECIAL).
     * {@code method = "*"} since pure-wildcard names aren't expanded.
     */
    @CInline
    @CRedirect(method = "*",
            target = @CTarget(value = "SUPER_SELF",
                    target = "(Ljava/lang/String;)Z", optional = true))
    private boolean hasResource(Object self, String name) {
        return UniminedFg2.hasResource(this.unimined$dirs, name);
    }

    /**
     * {@code super.getInputStreamByName(arg)} → dirs lookup. Must
     * replace the call outright — the super throws
     * {@code FileNotFoundException} on a miss, so a value-modifying
     * wrap would never see the dirs hit.
     */
    @CInline
    @CRedirect(method = "*",
            target = @CTarget(value = "SUPER_SELF",
                    target = "(Ljava/lang/String;)Ljava/io/InputStream;", optional = true))
    private InputStream getInputStreamByName(Object self, String name) throws IOException {
        return UniminedFg2.getInputStreamByName(this.unimined$dirs, name);
    }

    /**
     * {@code getResourceDomains} delegate — generated named after the
     * super's {@code ()Ljava/util/Set;} member (which the pack itself
     * doesn't declare), forwarding to the dirs scan.
     */
    @CSuperOverride("()Ljava/util/Set;")
    public Set<String> unimined$domains() {
        return UniminedFg2.getResourceDomains(this.unimined$dirs);
    }

    /**
     * {@code hasResource} delegate for eras where the pack lacks its
     * own {@code (Ljava/lang/String;)Z} override — skipped by
     * {@code ifAbsent} when it exists (the SUPER_SELF redirect covers
     * that method's super call instead).
     */
    @CSuperOverride(value = "(Ljava/lang/String;)Z", ifAbsent = true)
    protected boolean unimined$hasResource(String name) {
        return UniminedFg2.hasResource(this.unimined$dirs, name);
    }
}
