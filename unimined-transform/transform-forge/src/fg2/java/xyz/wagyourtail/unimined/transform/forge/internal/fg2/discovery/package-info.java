/**
 * MOD_CLASSES dev-mod discovery for FG2-era FML
 * ({@link xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2#PROP_MOD_CLASSES}).
 *
 * <p>FG2-era FML discovers classpath mods by scanning the launch
 * classloader's source list, and the mod classes themselves load
 * through it — but unimined dev launches keep mod output dirs off
 * {@code java.class.path} (they ride {@code MOD_CLASSES}, the
 * loom/modlauncher shape, which FG2-era FML never consumes: its
 * reader was ForgeGradle's GradleStart, absent from
 * userdev/foundation chains). {@link ModClassesTweakerTx} appends the
 * variable's members to the launch classloader (with the
 * constructor-time client hook in
 * {@link xyz.wagyourtail.unimined.transform.forge.internal.fg2.launch.FMLTweakerTx}
 * ahead of MixinBootstrap), replicating the classic FG dev classpath.
 * Runtime pairing heuristics still cannot represent the authoritative
 * metadata-first grouping — cleanroom's consecutive pairing in
 * particular scans the resource tree instead of the class tree — so
 * {@link ModDiscovererTx} supplements the pairs {@code MOD_CLASSES}
 * actually declares.
 *
 * <p>Disable at runtime with {@code -Dunimined.fg2.modClasses=false}.
 */
package xyz.wagyourtail.unimined.transform.forge.internal.fg2.discovery;
