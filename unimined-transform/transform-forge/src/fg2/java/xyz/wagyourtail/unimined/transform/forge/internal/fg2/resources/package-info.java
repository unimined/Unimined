/**
 * MOD_CLASSES resource lookup for FG2-era FML
 * ({@link xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2#PROP_MOD_CLASSES}).
 *
 * <p>In dev, FML sets {@code MOD_CLASSES} to {@code dir1;dir2} per mod
 * — the mod's {@code classes/} + {@code resources/} output dirs —
 * because there is no jar to read. FG2-era classes only partially
 * honor this: {@code FMLFolderResourcePack} reads resources from the
 * dirs but doesn't report them in its domain/has checks,
 * {@code LanguageRegistry} only scans the jar file, and
 * {@code DirectoryDiscoverer} never finds {@code mcmod.info} on a
 * classpath dir. These transformers close those gaps.
 *
 * <p>Disable at runtime with {@code -Dunimined.fg2.modClasses=false}.
 */
package xyz.wagyourtail.unimined.transform.forge.internal.fg2.resources;
