package xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt;

import java.io.*;
import java.lang.reflect.*;
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
    /**
     * File path of the LZMA-compressed dev→srg map fed to
     * {@code FMLDeobfuscatingRemapper} when {@link #PROP_RUNTIME_DEOBF}
     * is active — the {@code srg-mcp} job GradleStart used to do.
     */
    public static final String PROP_SRG_MAP = "unimined.fg2.srgMap";

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
    /**
     * {@code FMLDeobfTweaker} blackboard-probe wrapper — FML's
     * deobfuscated-environment test probes class names only, so an
     * srg jar (srg keeps mcp-style class names; only members are
     * renamed) registers as deobfuscated and the transformer block
     * gets skipped. When {@link #PROP_RUNTIME_DEOBF} is set the probe
     * is forced to {@code false} so the runtime deobf path runs.
     */
    public static boolean deobfEnvProbe(boolean deobfEnv) {
        setupRuntimeDeobf();
        return System.getProperty(PROP_RUNTIME_DEOBF) == null && deobfEnv;
    }

    public static String deobfTransformerNameOrNull(String name) {
        // map feeding is unconditional: reflection probes
        // (ObfuscationReflectionHelper) need srg→dev names resolved
        // in every dev launch, not only under runtime deobf —
        // setupRuntimeDeobf itself no-ops when PROP_SRG_MAP is unset
        setupRuntimeDeobf();
        if (System.getProperty(PROP_RUNTIME_DEOBF) == null) {
            // the dev jar is dev-namespace-named but FML's probes
            // (Class.forName/scanTarget/Remap annotations) carry srg
            // names — DeobfuscationTransformer was the era's
            // IClassNameTransformer, so substitute a name-only
            // translator riding the INSTANCE map just fed
            return "xyz.wagyourtail.unimined.transform.forge" +
                    ".internal.fg2.rt.UniminedFg2NameTransformer";
        }
        return name;
    }

    /**
     * Populate {@code FMLDeobfuscatingRemapper.INSTANCE} from the
     * {@link #PROP_SRG_MAP} LZMA file — reflective because the
     * remapper's binary name differs between the cpw (1.7.10) and
     * netforge (1.8+) eras. {@code setupLoadOnly} is the only
     * file-path entry point; eras lacking it keep their own setup.
     *
     * <p>{@code setupLoadOnly} leaves the remapper's {@code
     * classLoader} null, but its {@code parseField -> getFieldType}
     * reads owner-class bytes through it — so the field is pre-seeded
     * with the LaunchClassLoader that loaded this helper (the rt
     * classes are injected into the FML jar).
     */
    private static volatile boolean srgMapFed;

    private static void setupRuntimeDeobf() {
        String map = System.getProperty(PROP_SRG_MAP);
        if (srgMapFed || map == null || map.isEmpty()) return;
        srgMapFed = true;
        // the rt helper rides the *system* classpath, not the
        // LaunchClassLoader — pull the real one off `Launch`, and
        // resolve the remapper through IT: the class is a transformer
        // exclusion, so LCL defines its own copy and
        // Class.forName from here would bind (and feed) the detached
        // AppClassLoader copy, leaving the remapper the game actually
        // consults empty
        Object lcl;
        try {
            lcl = Class.forName("net.minecraft.launchwrapper.Launch")
                    .getField("classLoader").get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "unimined: could not reach Launch.classLoader", e);
        }
        for (String cls : new String[]{
                "cpw.mods.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper",
                "net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper"}) {
            Class<?> remapper;
            try {
                remapper = ((ClassLoader) lcl).loadClass(cls);
            } catch (ClassNotFoundException ignored) {
                continue;
            }
            try {
                Object inst = remapper.getField("INSTANCE").get(null);
                Field cl = remapper.getDeclaredField("classLoader");
                cl.setAccessible(true);
                cl.set(inst, lcl);
                remapper.getMethod("setupLoadOnly", String.class, boolean.class)
                        .invoke(inst, map, true);
                return;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "unimined: failed to feed " + map + " to " + cls, e);
            }
        }
        throw new IllegalStateException("unimined: " + PROP_SRG_MAP +
                " is set but no FMLDeobfuscatingRemapper is on the classpath");
    }

    // -----------------------------------------------------------------
    // srg-probe class loads — ObjectHolderRegistryTx redirect target
    // -----------------------------------------------------------------

    private static volatile Object remapperInstance;
    private static volatile Method remapperMap;
    private static volatile boolean remapperResolved;

    /**
     * {@code FMLDeobfuscatingRemapper.INSTANCE} resolved through
     * {@code Launch.classLoader} — the remapper is a transformer
     * exclusion, so the game's copy is LCL-defined and the
     * AppClassLoader one (what {@code Class.forName} here would bind)
     * is detached and never fed. Same resolution as
     * {@link #setupRuntimeDeobf}.
     */
    private static boolean resolveRemapper() {
        if (!remapperResolved) {
            remapperResolved = true;
            try {
                ClassLoader lcl = (ClassLoader) Class
                        .forName("net.minecraft.launchwrapper.Launch")
                        .getField("classLoader").get(null);
                for (String cls : new String[]{
                        "cpw.mods.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper",
                        "net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper"}) {
                    Class<?> c;
                    try {
                        c = lcl.loadClass(cls);
                    } catch (ClassNotFoundException ignored) {
                        continue;
                    }
                    remapperInstance = c.getField("INSTANCE").get(null);
                    remapperMap = c.getMethod("map", String.class);
                    break;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return remapperInstance != null;
    }

    /**
     * {@code Class.forName} substitute for srg-authored probe names —
     * {@code ObjectHolderRegistry.scanTarget} (and other FML sites)
     * probe hardcoded {@code net.minecraft.init.*} literals, but the
     * dev jar is dev-namespace-named and the JVM requires the loaded
     * class's name to equal the requested one, so the probe string
     * itself must be translated first. The fed {@code CL: srg dev}
     * table makes {@code map} srg->dev; names already dev-shaped
     * (mod ASMData class names) miss the table and pass through.
     */
    public static Class<?> probeClassForName(String name, boolean init,
                                             ClassLoader loader)
            throws ClassNotFoundException {
        setupRuntimeDeobf();
        String translated = name;
        if (resolveRemapper()) {
            try {
                translated = ((String) remapperMap.invoke(
                        remapperInstance,
                        name.replace('.', '/'))).replace('/', '.');
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return Class.forName(translated, init, loader);
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

    /**
     * {@code FMLTweaker.<init>} prologue (injected ahead of
     * {@code System.setSecurityManager}).
     *
     * <p>JDK8's {@code ClassLoader.checkPackageAccess} lazily
     * instantiates {@code java.lang.ClassLoader$1} — the
     * {@code PrivilegedAction} wrapping the package check — on the
     * first packaged {@code defineClass} after a
     * {@code SecurityManager} is installed. That inner class's own
     * load re-enters the app loader's {@code File.exists} →
     * {@code checkRead} → {@code checkPermission} path while the
     * class is mid-definition, throwing {@code ClassCircularityError:
     * java/lang/ClassLoader$1} (observed on 1.12.2 inside
     * {@code FMLTweaker.acceptOptions} → first post-SM
     * {@code loadClass}). Loading it now — before
     * {@code FMLSecurityManager} installs — removes the window.
     */
    public static void preloadSmRaceGuard() {
        try {
            Class.forName("java.lang.ClassLoader$1");
        } catch (Throwable ignored) {
        }
    }
}
