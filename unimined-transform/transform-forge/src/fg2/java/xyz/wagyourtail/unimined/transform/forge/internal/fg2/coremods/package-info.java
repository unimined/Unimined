/**
 * Classpath coremod discovery for FG2-era FML
 * ({@link xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2#PROP_AUTO_COREMODS}).
 *
 * <p>FG2-era FML discovers coremods by scanning <em>jar files</em> in
 * the mods folder — a classpath entry like a project's own
 * {@code classes/} dir or a dependency jar on the compile classpath
 * is never considered. In a dev environment that's exactly where the
 * coremod under development lives, so stock FG2 silently skips it.
 *
 * <p>These transformers append classpath entries to FML's coremod
 * candidate list and route cascading-tweak registration for them.
 * Disable at runtime with {@code -Dunimined.fg2.autoCoremods=false}
 * (checked live, no jar regeneration needed).
 */
package xyz.wagyourtail.unimined.transform.forge.internal.fg2.coremods;
