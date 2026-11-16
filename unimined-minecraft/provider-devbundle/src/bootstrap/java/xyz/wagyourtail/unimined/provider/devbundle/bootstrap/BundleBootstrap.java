package xyz.wagyourtail.unimined.provider.devbundle.bootstrap;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The paperclip stand-in embedded in unimined-exported dev bundles.
 *
 * NOT API — this class is compiled at {@code --release 8} (the module's
 * {@code compileBundleBootstrap} task) and copied byte-for-byte into the
 * {@code data/paperclip-mojang.jar} entry of every exported bundle, so the
 * bundle's reproducer runs on any era's JVM floor.
 *
 * Real paperclip applies binary patches to a staged vanilla jar; a unimined
 * export ships the already-patched jar inside this jar and the bootstrap
 * just extracts it — satisfying the same contract a consumer invokes:
 * {@code java -Dpaperclip.patchonly=true -cp paperclip.jar <Main-Class>}
 * in a work dir, producing {@code versions/<id>.jar} per
 * {@code META-INF/versions.list} plus the legacy {@code cache/patched*}
 * mirror. Without {@code -Dpaperclip.patchonly} the extracted jar and
 * libraries are launched like the real bundler.
 */
public final class BundleBootstrap {

    private BundleBootstrap() {
    }

    public static void main(String[] args) throws Exception {
        Path work = Paths.get("").toAbsolutePath();
        File self = new File(BundleBootstrap.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        JarFile jar = new JarFile(self);
        try {
            String id = versionId(jar);
            String rel = versionsPath(jar, id);
            Path out = work.resolve("versions").resolve(rel);
            copy(jar, "META-INF/versions/" + rel, out);
            // legacy (pre-bundler) output location — readers that don't
            // consult versions.list look for cache/patched*
            Path cache = work.resolve("cache");
            Files.createDirectories(cache);
            Files.copy(out, cache.resolve("patched-" + id + ".jar"),
                    StandardCopyOption.REPLACE_EXISTING);

            // extract bundled libraries for the launch classpath
            List<URL> classpath = new ArrayList<URL>();
            classpath.add(out.toUri().toURL());
            for (String[] row : readList(jar, "META-INF/libraries.list")) {
                Path lib = work.resolve("libraries").resolve(row[2]);
                if (copy(jar, "META-INF/libraries/" + row[2], lib)) {
                    classpath.add(lib.toUri().toURL());
                }
            }

            if (!"true".equals(System.getProperty("paperclip.patchonly"))) {
                String mainClass = readString(jar, "META-INF/main-class").trim();
                launch(mainClass, classpath, args);
            }
        } finally {
            jar.close();
        }
    }

    /** Run the extracted server on an isolated classpath — the
     *  paperclip-jar classes must not leak onto it. */
    private static void launch(String mainClass, List<URL> classpath,
                               String[] args) throws Exception {
        URLClassLoader loader = new URLClassLoader(
                classpath.toArray(new URL[0]),
                BundleBootstrap.class.getClassLoader().getParent());
        try {
            Thread.currentThread().setContextClassLoader(loader);
            Method main = loader.loadClass(mainClass)
                    .getMethod("main", String[].class);
            main.invoke(null, (Object) args);
        } finally {
            loader.close();
        }
    }

    /** Copy [entryPath] out of the jar to [dest] — false when absent. */
    private static boolean copy(JarFile jar, String entryPath, Path dest)
            throws Exception {
        JarEntry entry = jar.getJarEntry(entryPath);
        if (entry == null) {
            return false;
        }
        Files.createDirectories(dest.getParent());
        InputStream in = jar.getInputStream(entry);
        try {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            in.close();
        }
        return true;
    }

    /** A whole jar entry as text. */
    private static String readString(JarFile jar, String entryPath)
            throws Exception {
        JarEntry entry = jar.getJarEntry(entryPath);
        if (entry == null) {
            throw new IllegalStateException(
                    "bundle paperclip is missing " + entryPath);
        }
        InputStream in = jar.getInputStream(entry);
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) >= 0) {
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    /** `hash \t id \t path` rows (comment/blank lines skipped). */
    private static List<String[]> readList(JarFile jar, String entryPath)
            throws Exception {
        JarEntry entry = jar.getJarEntry(entryPath);
        List<String[]> rows = new ArrayList<String[]>();
        if (entry == null) {
            return rows;
        }
        for (String line : readString(jar, entryPath).split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.trim().split("\t");
            if (cols.length >= 3) {
                rows.add(cols);
            }
        }
        return rows;
    }

    private static final Pattern ID_PATTERN =
            Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    /** The bundle's version id — `version.json`'s `id` field. */
    private static String versionId(JarFile jar) throws Exception {
        Matcher m = ID_PATTERN.matcher(readString(jar, "version.json"));
        if (!m.find()) {
            throw new IllegalStateException(
                    "bundle paperclip version.json has no id");
        }
        return m.group(1);
    }

    /** The versions.list row keyed by [id] → its path column. */
    private static String versionsPath(JarFile jar, String id)
            throws Exception {
        for (String[] row : readList(jar, "META-INF/versions.list")) {
            if (row[1].equals(id)) {
                return row[2];
            }
        }
        throw new IllegalStateException(
                "bundle paperclip versions.list has no entry for '" + id + "'");
    }

}
