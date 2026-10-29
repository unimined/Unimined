package xyz.wagyourtail.unimined.transform.forge.internal.fg2.resources;

import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CModifyConstant;

/**
 * Exclude {@code META-INF/} entries from FML's class-file discovery
 * pattern.
 *
 * <p><b>Why:</b> dev classpaths carry plain library jars, and
 * multi-release jars ship {@code META-INF/versions/N/*.class} entries
 * (e.g. {@code module-info.class}) compiled for newer Java versions.
 * {@code JarDiscoverer.findClassesASM} feeds every
 * {@link ITypeDiscoverer#classFile}-matching entry to
 * {@code ASMModParser} — ASM 5 can't read the newer class file
 * versions, the whole jar is then logged as "probably a corrupt zip"
 * and skipped. No real mod classes ever live under {@code META-INF/},
 * so excluding the prefix is safe.
 *
 * <p>The pattern is an {@code ITypeDiscoverer} interface constant
 * shared by {@code JarDiscoverer} and {@code DirectoryDiscoverer} —
 * one constant-swap in {@code <clinit>} covers both.
 */
@CTransformer(name = {
        "net.minecraftforge.fml.common.discovery.ITypeDiscoverer",
        "cpw.mods.fml.common.discovery.ITypeDiscoverer",
})
public class ITypeDiscovererTx {

    // `optional` — earlier/other FML variants may use a different
    // class-file pattern; no match means leave it alone.
    @CInline
    @CModifyConstant(
            method = "<clinit>",
            stringValue = "[^\\s\\$]+(\\$[^\\s]+)?\\.class$",
            optional = true
    )
    private static String skipMetaInfEntries(String pattern) {
        return "(?!META-INF/)" + pattern;
    }
}
