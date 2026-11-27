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
     * ({@code label%%path} pairs joined by pathSeparator): once one
     * pair's path matches the mod source, every existing dir carrying
     * the same label forms that mod's resource-root union.
     */
    public static File[] getDirectories(File modSource) {
        return getDirectories(modSource, System.getenv("MOD_CLASSES"));
    }

    /**
     * Parse {@code MOD_CLASSES} for {@code modSource} — split out from
     * {@link #getDirectories(File)} so the behavior is testable without
     * mutating the process environment. Entries are {@code label%%path}
     * pairs. Nonexistent and malformed members are ignored: Gradle does
     * not create a resources output directory for a source set with no
     * resources, but that nominal output dir still participates in
     * source-set output serialization. FG2's recursive language scan
     * dereferences {@code listFiles()} unconditionally, so passing the
     * absent dir through crashes mod construction before FML assigns
     * its event channels. The label match includes the {@code %%}
     * terminator so labels that share a prefix (for example {@code mod}
     * and {@code mod2}) do not merge.
     */
    public static File[] getDirectories(File modSource, String modClasses) {
        if (!enabled(PROP_MOD_CLASSES) || modClasses == null) {
            return new File[]{modSource};
        }
        String[] entries = modClasses.split(File.pathSeparator);
        List<File> out = new ArrayList<>();
        String label = null;
        for (String entry : entries) {
            int split = entry.indexOf("%%");
            if (split < 0) continue;
            if (new File(entry.substring(split + 2)).equals(modSource)) {
                label = entry.substring(0, split);
                break;
            }
        }
        if (label == null) {
            return new File[]{modSource};
        }
        String prefix = label + "%%";
        for (String entry : entries) {
            if (entry.startsWith(prefix)) {
                File dir = new File(entry.substring(prefix.length()));
                if (dir.isDirectory()) {
                    out.add(dir);
                }
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

    // -----------------------------------------------------------------
    // MOD_CLASSES dev-mod discovery — tweaker/discovery injections
    // -----------------------------------------------------------------

    /**
     * {@code FMLTweaker.injectIntoClassLoader} HEAD prologue: append
     * every existing {@code MOD_CLASSES} member to the launch
     * classloader's URLs. FG2-era FML discovers classpath mods by
     * scanning the loader's source list, and {@code ModClassLoader}
     * delegates classloading to it — but unimined dev launches keep
     * mod output dirs off {@code java.class.path} (they ride
     * {@code MOD_CLASSES} instead, the loom/modlauncher shape), so
     * without this the mod's classes are neither discoverable nor
     * loadable. Injecting the dirs replicates the classic FG dev
     * state — where every output dir was on the classpath — ahead of
     * coremod setup and discovery. Client tweakers also receive a
     * constructor-time injection before MixinBootstrap; this hook is
     * the idempotent interface-method fallback.
     *
     * <p>Foundation's {@code addURL} dedups; vanilla launchwrapper
     * accepts the rare duplicate harmlessly (URLClassPath dedups
     * internally, and FML's candidate list dedups by file). Never
     * fatal: a failure here leaves pre-injection behavior, which is
     * strictly better than crashing the launch.
     */
    public static void injectModClassUrls(Object classLoader) {
        injectModClassUrls(classLoader, System.getenv("MOD_CLASSES"));
    }

    /**
     * Constructor-time form of {@link #injectModClassUrls(Object)} for
     * {@code FMLTweaker.<init>}. MixinBootstrap runs inside that
     * constructor — before {@code injectIntoClassLoader} — so configs
     * and config-plugin classes must already resolve through the
     * launch loader there. Loading them through the launch loader (not
     * the app loader) is also what keeps config plugins in the same
     * classloader as Mixin's {@code IMixinConfigPlugin} interface.
     * Reflective because the helper must not initialize LaunchWrapper
     * merely by being linked; failure leaves the later tweaker hook as
     * the pre-existing fallback.
     */
    public static void injectModClassUrlsIntoLaunch() {
        try {
            Class<?> launch = Class.forName(
                    "net.minecraft.launchwrapper.Launch", false,
                    UniminedFg2.class.getClassLoader());
            injectModClassUrls(launch.getField("classLoader").get(null));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Parse-and-inject core of {@link #injectModClassUrls(Object)}
     * split out for testability — entries are {@code label%%path}
     * pairs joined by pathSeparator; nonexistent members and repeats
     * are skipped.
     */
    public static void injectModClassUrls(Object classLoader, String modClasses) {
        if (!enabled(PROP_MOD_CLASSES) || modClasses == null
                || classLoader == null) {
            return;
        }
        Method addUrl;
        try {
            addUrl = classLoader.getClass()
                    .getMethod("addURL", java.net.URL.class);
        } catch (Throwable t) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (String entry : modClasses.split(File.pathSeparator)) {
            int split = entry.indexOf("%%");
            if (split < 0) continue;
            String path = entry.substring(split + 2);
            if (path.isEmpty() || !seen.add(path)) continue;
            File f = new File(path);
            if (!f.exists()) continue;
            try {
                addUrl.invoke(classLoader, f.toURI().toURL());
            } catch (Throwable ignored) {
                // one bad member must not lose the rest — discovery
                // without it matches pre-injection behavior
            }
        }
    }

    /**
     * {@code CleanroomModDiscoverer.addClasspathCandidates} RETURN
     * epilogue: pair each {@code MOD_CLASSES} group member the native
     * pairing logic cannot see into an explicit candidate.
     *
     * <p><b>Why:</b> cleanroom collects classpath directories into
     * groups keyed by the final path segment and pairs each
     * {@code classes/…/<name>} with a sibling literally shaped
     * {@code …/resources/<name>} — a group without that sibling is
     * skipped entirely. Gradle layouts only satisfy it when a source
     * set's own resources output exists ({@code build/resources/main}
     * pairs {@code build/classes/java/main} as the
     * <i>resource</i> half of the preceding pair instead of a scan
     * root — the serialization is metadata-first, so the consecutive
     * heuristic can never represent the layout). The group's actual shape is already
     * declared in {@code MOD_CLASSES}: one label is one logical mod,
     * metadata-first ordered. For each label this pairs every member
     * dir with the group's metadata-bearing root — exactly what the
     * native logic approximates — as a reflective {@code ModCandidate}
     * on the discoverer's own list.
     *
     * <p>The constructor is resolved reflectively because the shape
     * differs across the supported jars. Cleanroom's protected two-arg
     * {@code (classRoot, resourceRoot)} form is preferred — it is the
     * only shape that carries the distinct resource root. Vanilla/cpw
     * FML carry only the five-arg {@code (classRoot, modContainer,
     * ContainerType, boolean, boolean)} form; its second file is the
     * candidate identity, not a resource root, so the fallback receives
     * the class root twice and metadata lookup remains the job of the
     * {@code DirectoryDiscoverer} redirect. Never fatal for the same
     * reason as the tweaker injection.
     */
    public static void injectModClassCandidates(Object discoverer) {
        injectModClassCandidates(discoverer, System.getenv("MOD_CLASSES"));
    }

    /**
     * Parse-and-pair core of
     * {@link #injectModClassCandidates(Object)} split out for
     * testability. Reflectively resolves the runtime's
     * {@code ModCandidate} constructor — preferring cleanroom's
     * resource-aware two-arg {@code (classRoot, resourceRoot)} shape
     * (protected there, so reflective access is required), falling
     * back to vanilla/cpw FML's five-arg {@code (classRoot,
     * modContainer, ContainerType, boolean, boolean)} shape with
     * {@code DIR}/{@code isMinecraft=false}/{@code classpath=true} —
     * then adds each pair through the discoverer's own
     * {@code addCandidate} so its dedup rules apply. Dirs already
     * claimed as a candidate's
     * scan root are skipped, so a native pairing that happened to
     * land correctly is never duplicated (a duplicate scan of the
     * same dir would discover the mod twice).
     */
    public static void injectModClassCandidates(Object discoverer,
                                                String modClasses) {
        if (!enabled(PROP_MOD_CLASSES) || modClasses == null) {
            return;
        }
        try {
            ClassLoader l = discoverer.getClass().getClassLoader();
            Class<?> modCandidate = null;
            for (String candidateClass : new String[]{
                    "net.minecraftforge.fml.common.discovery.ModCandidate",
                    "cpw.mods.fml.common.discovery.ModCandidate"}) {
                try {
                    modCandidate = Class.forName(candidateClass, false, l);
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (modCandidate == null) return;
            Method addCandidate;
            try {
                addCandidate = discoverer.getClass().getMethod(
                        "addCandidate", modCandidate);
            } catch (Throwable t) {
                return;
            }
            Constructor<?> ctor5 = null;
            Object dirType = null;
            try {
                String basePackage = modCandidate.getName().substring(0,
                        modCandidate.getName().indexOf(".discovery."));
                Class<?> containerType = Class.forName(
                        basePackage + ".discovery.ContainerType", false, l);
                ctor5 = modCandidate.getConstructor(File.class, File.class,
                        containerType, boolean.class, boolean.class);
                dirType = containerType.getField("DIR").get(null);
            } catch (Throwable ignored) {
            }
            Constructor<?> ctor2 = null;
            try {
                // cleanroom's resource-aware constructor is protected:
                // getConstructor would miss it even though the runtime
                // deliberately makes it available to ModDiscoverer. It is
                // the only constructor whose second argument is the
                // resource root; the five-arg form's second argument is
                // the mod-container identity instead.
                ctor2 = modCandidate.getDeclaredConstructor(
                        File.class, File.class);
                ctor2.setAccessible(true);
            } catch (Throwable ignored) {
                ctor2 = null;
            }
            if (ctor5 == null && ctor2 == null) return;
            // scan roots the native pass already claimed
            Set<File> claimed = new HashSet<>();
            try {
                Method getClassPathRoot =
                        modCandidate.getMethod("getClassPathRoot");
                java.lang.reflect.Field candidatesF = null;
                for (java.lang.reflect.Field f
                        : discoverer.getClass().getDeclaredFields()) {
                    if (List.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object v = f.get(discoverer);
                        if (v instanceof List
                                && !((List<?>) v).isEmpty()
                                && modCandidate.isInstance(
                                        ((List<?>) v).get(0))) {
                            candidatesF = f;
                            break;
                        }
                    }
                }
                if (candidatesF != null) {
                    for (Object c
                            : (List<?>) candidatesF.get(discoverer)) {
                        Object classPathRoot = getClassPathRoot.invoke(c);
                        if (classPathRoot instanceof File) {
                            claimed.add((File) classPathRoot);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            Map<File, File> candidatePairs = modClassCandidatePairs(modClasses);
            for (Map.Entry<File, File> pair : candidatePairs.entrySet()) {
                File classDir = pair.getKey();
                if (!claimed.add(classDir)) continue;
                try {
                    // Prefer the two-arg form whenever present: it is the
                    // only shape that carries a distinct resource root.
                    // The five-arg fallback cannot represent that root —
                    // its second File is the candidate identity scanned by
                    // DirectoryDiscoverer, so passing the resource root
                    // there makes addCandidate dedup every supplement
                    // against the metadata dir. Passing classDir preserves
                    // correct scanning; DirectoryDiscovererTx resolves
                    // mcmod.info across the MOD_CLASSES group instead.
                    Object candidate = ctor2 != null
                            ? ctor2.newInstance(classDir, pair.getValue())
                            : ctor5.newInstance(classDir, classDir,
                                    dirType, false, true);
                    addCandidate.invoke(discoverer, candidate);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            // never fatal — discovery without the supplement is the
            // pre-fix behaviour
        }
    }

    /**
     * The {@code classDir -> resourceRoot} pairings one
     * {@code MOD_CLASSES} string declares, in scan order — split out
     * of {@link #injectModClassCandidates} so the pairing rules are
     * testable without a ModCandidate on the classpath. Per label
     * group: existing dirs only; the resource root is the first
     * member carrying {@code mcmod.info} (the serialization is
     * metadata-first, so this is normally the leader), falling back
     * to the first member; every other member pairs with it as a
     * scan target. The root itself is not scanned unless it is the
     * group's only existing dir — then it pairs with itself, the
     * vanilla single-dir shape. A dir repeated across labels pairs
     * with its first group's root.
     */
    public static Map<File, File> modClassCandidatePairs(String modClasses) {
        Map<File, File> out = new LinkedHashMap<>();
        if (modClasses == null) return out;
        // group dirs by label, first-seen order — the serialization
        // is metadata-first within a label, so the group's resource
        // root leads
        Map<String, List<File>> groups = new LinkedHashMap<>();
        for (String entry : modClasses.split(File.pathSeparator)) {
            int split = entry.indexOf("%%");
            if (split < 0) continue;
            String path = entry.substring(split + 2);
            if (path.isEmpty()) continue;
            File dir = new File(path).toPath().toAbsolutePath()
                    .normalize().toFile();
            List<File> members = groups.computeIfAbsent(
                    entry.substring(0, split), k -> new ArrayList<>());
            if (!members.contains(dir)) {
                members.add(dir);
            }
        }
        for (List<File> members : groups.values()) {
            List<File> dirs = new ArrayList<>();
            for (File member : members) {
                if (member.isDirectory()) dirs.add(member);
            }
            if (dirs.isEmpty()) continue;
            File resourceRoot = null;
            for (File dir : dirs) {
                if (new File(dir, "mcmod.info").isFile()) {
                    resourceRoot = dir;
                    break;
                }
            }
            if (resourceRoot == null) resourceRoot = dirs.get(0);
            for (File classDir : dirs) {
                // the resource root is the metadata half of the pair,
                // not a scan target — unless it is all the group has
                if (classDir.equals(resourceRoot) && dirs.size() > 1) {
                    continue;
                }
                out.putIfAbsent(classDir, resourceRoot);
            }
        }
        return out;
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
