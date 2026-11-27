package xyz.wagyourtail.unimined.transform.forge.internal.fg2.discovery;

import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2;

/**
 * Dev-candidate pairing — the discovery half of MOD_CLASSES
 * injection.
 *
 * <p><b>Why:</b> FG2-era {@code findClasspathMods} implementations
 * approximate dev layouts heuristically and inconsistently: vanilla
 * jars tend to treat every directory as a self-contained candidate,
 * while cleanroom's consecutive-pairing variant interprets one dir as
 * the class-scan root and the next as its resource root. Neither
 * heuristic represents unimined's authoritative {@code MOD_CLASSES}
 * grouping — a metadata-first serialization in which
 * {@code resources/main} leads but {@code classes/java/main} and any
 * era source-set dirs are the class-scan roots. In particular,
 * cleanroom pairs the resources dir as the <i>class</i> root and the
 * real class dir as its resource root, so the mod's entrypoint is
 * never scanned even once the dirs are on the classloader.
 *
 * <p>The epilogue supplements what the heuristic mis-paired using
 * the authoritative grouping already declared in {@code MOD_CLASSES}
 * (one label is one logical mod, metadata-first): every member dir
 * becomes a {@code ModCandidate} against the group's
 * metadata-bearing root, added through the discoverer's own public
 * {@code addCandidate} so its dedup keeps precedence rules intact.
 * Mis-paired natural candidates scan the wrong trees and yield
 * nothing, so supplementing beats replacing. The helper resolves
 * the constructor reflectively — the five-arg vanilla shape here,
 * the two-arg shape newer cleanroom carries — and no-ops when
 * neither exists.
 */
@CTransformer(name = {
        "net.minecraftforge.fml.common.discovery.ModDiscoverer",
        "cpw.mods.fml.common.discovery.ModDiscoverer",
})
public class ModDiscovererTx {

    /**
     * {@code findClasspathMods(ModClassLoader)} — after the native
     * pass, so supplemented candidates only add what consecutive
     * pairing could not represent.
     */
    @CInline
    @CInject(method = "findClasspathMods*", target = @CTarget("RETURN"))
    private void injectModClassCandidates() {
        UniminedFg2.injectModClassCandidates(this);
    }
}
