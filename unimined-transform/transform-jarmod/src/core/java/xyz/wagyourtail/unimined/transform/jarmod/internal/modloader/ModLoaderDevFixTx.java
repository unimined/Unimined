package xyz.wagyourtail.unimined.transform.jarmod.internal.modloader;

import net.lenni0451.classtransform.annotations.CLocalVariable;
import net.lenni0451.classtransform.annotations.CSlice;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import xyz.wagyourtail.unimined.transform.internal.classtransform.CExtractRange;
import xyz.wagyourtail.unimined.transform.internal.classtransform.CReplaceCalls;
import xyz.wagyourtail.unimined.transform.internal.classtransform.CReplaceRange;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Port of 1.x {@code ModLoaderPatches} — URI-is-not-hierarchical fix
 * plus loading mods from non-root packages, for both variants:
 * <ul>
 *   <li>old ModLoader: {@code <clinit>} builds
 *       {@code new File(codesource.toURI())} and scans it inline —
 *       {@code CReplaceRange} swaps the codesource computation for
 *       the classpath loop, {@code CExtractRange} lifts the scan tail
 *       into a synthesized {@code readFromClassPath(File)}.</li>
 *   <li>newer ModLoader: same codesource ctor lives in {@code init()}
 *       followed by a {@code readFromClassPath} call — {@code toURI}
 *       is repointed at a real classpath entry and the call is
 *       expanded to the full classpath loop.</li>
 *   <li>both: {@code readFromClassPath}'s {@code listFiles()} becomes
 *       a recursive walk, {@code getName()} becomes a path relative
 *       to the scanned root, and {@code startsWith("mod_")} becomes a
 *       package-path-aware match.</li>
 * </ul>
 * Every member is annotated — nothing is copied verbatim except the
 * helpers their annotations declare. All stubs self-gate on what the
 * target actually contains, so a single transformer covers both
 * variants in one pass.
 */
@CTransformer(name = {"ModLoader"})
public class ModLoaderDevFixTx {

    /** javac declaration + extract signature: on old ModLoader the
        clinit tail becomes this method; on newer ones it exists
        already and the handler skips. */
    @CExtractRange(
            method = "<clinit>",
            after = @CTarget(value = "OPCODE", target = "ASTORE", ordinal = 0))
    private static void readFromClassPath(@CLocalVariable(index = 0) File file) {}

    /** old variant: replace the codesource ctor in clinit with the
        classpath loop */
    @CReplaceRange(
            method = "<clinit>",
            replace = @CSlice(
                    from = @CTarget(value = "OPCODE", target = "NEW"),
                    to = @CTarget(value = "OPCODE", target = "ASTORE")))
    private static void readClasspath() {
        for (String s : System.getProperty("java.class.path").split(File.pathSeparator)) {
            readFromClassPath(new File(s));
        }
    }

    /** newer variant: the codesource URI can't resolve a dev classpath
        — point the File var at the first real classpath entry */
    @CReplaceCalls(
            method = "init",
            target = @CTarget(
                    value = "INVOKE",
                    target = "Ljava/net/URL;toURI()Ljava/net/URI;",
                    optional = true))
    private static URI safeToUri(URL self) throws URISyntaxException {
        return new File(System.getProperty("java.class.path")
                .split(File.pathSeparator)[0]).toURI();
    }

    /** newer variant: expand the single readFromClassPath call in
        init to the whole classpath */
    @CReplaceCalls(
            method = "init",
            target = @CTarget(
                    value = "INVOKE",
                    target = "LModLoader;readFromClassPath(Ljava/io/File;)V",
                    optional = true))
    private static void readFromClasspath(File ignored) {
        for (String path : System.getProperty("java.class.path").split(File.pathSeparator)) {
            readFromClassPath(new File(path));
        }
    }

    /** package fix 1/3: recursive listing so mods in package dirs are
        discovered (dev classpath output dirs). <clinit> binding covers
        the old variant — the redirect rides the extracted tail into
        the synthesized method; readFromClassPath covers the newer. */
    @CReplaceCalls(
            method = "readFromClassPath",
            target = @CTarget(
                    value = "INVOKE",
                    target = "Ljava/io/File;listFiles()[Ljava/io/File;",
                    optional = true))
    private static File[] listAllFiles(File dir) {
        List<File> out = new ArrayList<File>();
        Deque<File> stack = new ArrayDeque<File>();
        stack.push(dir);
        while (!stack.isEmpty()) {
            File[] children = stack.pop().listFiles();
            if (children == null) continue;
            for (File c : children) {
                out.add(c);
                if (c.isDirectory()) stack.push(c);
            }
        }
        return out.toArray(new File[0]);
    }

    /** package fix 2/3: the listed file's name is passed to addMod —
        give it the path relative to the scan root, matching jar entry
        form; scoped to after listAllFiles so earlier getName() uses
        (zip checks) stay */
    @CReplaceCalls(
            method = "readFromClassPath",
            slice = @CSlice(
                    from = @CTarget(
                            value = "INVOKE",
                            target = "LModLoader;listAllFiles(Ljava/io/File;)[Ljava/io/File;"),
                    to = @CTarget(value = "")),
            target = @CTarget(
                    value = "INVOKE",
                    target = "Ljava/io/File;getName()Ljava/lang/String;",
                    optional = true))
    private static String relPath(File f, @CLocalVariable(index = 0) File root) {
        return f.toPath().relativize(root.toPath()).toString();
    }

    /** package fix 3/3: name is now a path — the mod_ prefix check
        must accept a package prefix; also covers the .class suffix */
    @CReplaceCalls(
            method = "readFromClassPath",
            slice = @CSlice(
                    from = @CTarget(
                            value = "INVOKE",
                            target = "LModLoader;listAllFiles(Ljava/io/File;)[Ljava/io/File;"),
                    to = @CTarget(value = "")),
            target = @CTarget(
                    value = "INVOKE",
                    target = "Ljava/lang/String;startsWith(Ljava/lang/String;)Z",
                    optional = true))
    private static boolean isModClass(String name, String ignored) {
        return name.matches("(?:.*[/\\\\]|^)mod_.*\\.class");
    }
}
