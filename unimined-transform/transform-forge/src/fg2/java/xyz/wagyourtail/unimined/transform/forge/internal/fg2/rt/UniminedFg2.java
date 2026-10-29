package xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt;

import java.io.*;
import java.util.*;

/**
 * Runtime helpers injected into FG2-era dev environments (1.x's
 * `unimined$*` methods, previously emitted as raw ASM bodies inside
 * forge classes — now compiled Java spliced into the dev jar by the
 * `forge-fg2-fixes` step and referenced via INVOKESTATIC).
 *
 * <p>Everything here is classpath/resource shim logic for making
 * FG2-era FML behave like the modern dev classpath: resources and
 * language files found through {@code MOD_CLASSES}, coremods
 * discovered on {@code java.class.path}.
 */
public final class UniminedFg2 {

    /**
     * Runtime kill-switches — read per call so a {@code -D} flag
     * changes behavior without regenerating the dev jar. All default
     * to 1.x behavior.
     */
    public static final String PROP_AUTO_COREMODS = "unimined.fg2.autoCoremods";
    /**
     * Gate for {@link #getDirectories} MOD_CLASSES expansion.
     */
    public static final String PROP_MOD_CLASSES = "unimined.fg2.modClasses";
    /**
     * Opt-in: keep FML's runtime DeobfuscationTransformer.
     */
    public static final String PROP_RUNTIME_DEOBF = "unimined.fg2.runtimeDeobf";

    private UniminedFg2() {
    }

    private static boolean enabled(String prop) {
        return !"false".equalsIgnoreCase(System.getProperty(prop));
    }

    /**
     * {@code FMLDeobfTweaker} LDC replacement — returns {@code null}
     * (strip the transformer) unless {@link #PROP_RUNTIME_DEOBF} is
     * set, in which case the original class name passes through.
     */
    public static String deobfTransformerNameOrNull(String name) {
        return System.getProperty(PROP_RUNTIME_DEOBF) == null ? null : name;
    }

    /**
     * {@code CoreModManager.unimined$addClasspath}: the full
     * {@code java.class.path} entries plus the discovered files —
     * coremods living on the build classpath get scanned as
     * coremod candidates.
     */
    public static File[] addClasspath(File[] files) {
        if (!enabled(PROP_AUTO_COREMODS)) return files;
        ArrayList<File> list = new ArrayList<>();
        for (String p : System.getProperty("java.class.path").split(File.pathSeparator)) {
            list.add(new File(p));
        }
        list.addAll(Arrays.asList(files));
        return list.toArray(new File[0]);
    }

    /**
     * {@code CoreModManager.unimined$isOnClasspath}.
     */
    public static boolean isOnClasspath(File file) {
        if (!enabled(PROP_AUTO_COREMODS)) return false;
        for (String p : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (file.equals(new File(p))) return true;
        }
        return false;
    }

    /**
     * {@code LoadController.unimined$getDirectories}: resource roots
     * for a mod source dir, parsed from {@code MOD_CLASSES}
     * ({@code classesDir%%resourcesDir} pairs joined by pathSeparator —
     * the GradleStart convention): entries whose classes dir matches
     * the mod source map to their resources dirs.
     */
    public static File[] getDirectories(File modSource) {
        String modClasses = System.getenv("MOD_CLASSES");
        if (!enabled(PROP_MOD_CLASSES) || modClasses == null) {
            return new File[]{modSource};
        }
        String[] entries = modClasses.split(File.pathSeparator);
        List<File> out = new ArrayList<>();
        String classesDir = null;
        for (String entry : entries) {
            String[] pair = entry.split("%%", 2);
            if (new File(pair[1]).equals(modSource)) {
                classesDir = pair[0];
                break;
            }
        }
        if (classesDir == null) {
            return new File[]{modSource};
        }
        for (String entry : entries) {
            if (entry.startsWith(classesDir)) {
                out.add(new File(entry.substring(classesDir.length() + 2)));
            }
        }
        return out.toArray(new File[0]);
    }

    /**
     * {@code FolderResourcePack.unimined$getResourceDomains}:
     * {@code assets/} subdirectory names across all resource roots
     * (lowercase only, 1.x behavior).
     */
    public static Set<String> getResourceDomains(File[] dirs) {
        Set<String> out = new HashSet<>();
        for (File dir : dirs) {
            File assets = new File(dir, "assets/");
            if (!assets.isDirectory()) continue;
            File[] subdirs = assets.listFiles(f -> f != null && f.isDirectory());
            if (subdirs == null) continue;
            for (File sub : subdirs) {
                String rel = assets.toURI().relativize(sub.toURI()).getPath();
                if (rel.equals(rel.toLowerCase())) {
                    out.add(rel.substring(0, rel.length() - 1));
                }
            }
        }
        return out;
    }

    /**
     * {@code ModContainer.getSource()} via reflection — the
     * {@code ModContainer} type isn't visible to injected code (its
     * binary name varies per era), while {@code getSource} is stable
     * FML API in both the cpw and netforge worlds.
     */
    public static File getContainerSource(Object container) {
        try {
            return (File) container.getClass().getMethod("getSource").invoke(container);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to resolve ModContainer#getSource", e);
        }
    }

    /**
     * {@code FolderResourcePack.unimined$hasResource}.
     */
    public static boolean hasResource(File[] dirs, String name) {
        for (File dir : dirs) {
            if (new File(dir, name).isFile()) return true;
        }
        return false;
    }

    /**
     * {@code FolderResourcePack.unimined$getInputStreamByName}.
     */
    public static InputStream getInputStreamByName(File[] dirs, String name)
            throws FileNotFoundException {
        for (File dir : dirs) {
            File f = new File(dir, name);
            if (f.exists()) {
                return new BufferedInputStream(new FileInputStream(f));
            }
        }
        throw new FileNotFoundException(name);
    }

    /**
     * {@code DirectoryDiscoverer.unimined$findMcModInfo}: find
     * {@code relative} (e.g. {@code mcmod.info}) across the mod's
     * resource roots, falling back to the mod source itself.
     */
    public static File findMcModInfo(File modSource, String relative) {
        for (File dir : getDirectories(modSource)) {
            File f = new File(dir, relative);
            if (f.exists()) return f;
        }
        return new File(modSource, relative);
    }
}
