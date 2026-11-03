package xyz.wagyourtail.unimined.transform.jarmod.internal.fg1;

import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import xyz.wagyourtail.unimined.transform.jarmod.internal.fg1.rt.UniminedFg1ChecksumFix;

/**
 * Rewrites {@code CoreFMLLibraries.checksums} so FML's downloader
 * accepts the artifacts unimined actually ships in {@code runDir/lib}.
 * The class holds parallel {@code libraries[]}/{@code checksums[]}
 * {@code String} arrays built in {@code <clinit>}; for every shipped
 * lib whose content differs from the era's original (asm-5.2
 * substitution, maven-resolved fmllibs) the matching {@code checksums}
 * slot is replaced with the artifact's SHA1 — integrity checking stays
 * on, real corruption still fails the hash. Non-LDC slots (e.g.
 * {@code FMLInjectionData.deobfuscationDataHash} on 1.5.2) keep their
 * expression — only index alignment matters.
 *
 * Applied build-time via {@code ClassTransformJarFix}: the injected
 * call tail-ends {@code <clinit>} and defers to
 * {@link UniminedFg1ChecksumFix} (spliced into the jar), which reads
 * the per-env sums from the {@code /unimined/fg1-lib-sums.txt} jar
 * resource written by {@code FMLChecksumPatchProcessor}.
 */
@CTransformer(name = "cpw.mods.fml.relauncher.CoreFMLLibraries")
public class CoreFMLLibrariesChecksumTx {

    @CShadow
    private static String[] libraries;

    @CShadow
    private static String[] checksums;

    @CInject(method = "<clinit>()V",
            target = @CTarget(value = "RETURN", shift = CTarget.Shift.BEFORE))
    private static void unimined$fixChecksums() {
        UniminedFg1ChecksumFix.apply(libraries, checksums);
    }

}
