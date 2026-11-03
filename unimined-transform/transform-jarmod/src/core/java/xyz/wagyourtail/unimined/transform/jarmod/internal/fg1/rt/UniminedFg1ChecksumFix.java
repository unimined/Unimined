package xyz.wagyourtail.unimined.transform.jarmod.internal.fg1.rt;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Spliced-into-the-jar runtime helper for
 * {@code CoreFMLLibrariesChecksumTx}. Called from the injected tail
 * of {@code CoreFMLLibraries.<clinit>}; rewrites the {@code checksums}
 * array (index-aligned with {@code libraries}) to the SHA1s of the
 * artifacts unimined ships, read from the
 * {@code /unimined/fg1-lib-sums.txt} jar resource that
 * {@code FMLChecksumPatchProcessor} writes at transform time.
 *
 * Self-contained: runs inside FML's classloader on the game JVM, so
 * it may only touch JDK classes.
 */
public final class UniminedFg1ChecksumFix {

    private static final String RESOURCE = "/unimined/fg1-lib-sums.txt";

    private UniminedFg1ChecksumFix() {}

    public static void apply(String[] libraries, String[] checksums) {
        Map<String, String> sums = readSums();
        if (sums.isEmpty()) return;
        for (int i = 0; i < libraries.length && i < checksums.length; i++) {
            String sha = sums.get(libraries[i]);
            if (sha != null) checksums[i] = sha;
        }
    }

    private static Map<String, String> readSums() {
        Map<String, String> out = new HashMap<>();
        try {
            InputStream in = UniminedFg1ChecksumFix.class.getResourceAsStream(RESOURCE);
            if (in == null) return out;
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    int sep = line.indexOf('=');
                    if (sep > 0) out.put(line.substring(0, sep), line.substring(sep + 1));
                }
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            // FML's own failure path handles genuinely missing/bad libs —
            // a broken sums file must not keep the game from launching
            return new HashMap<>();
        }
        return out;
    }

}
