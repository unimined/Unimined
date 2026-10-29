/**
 * Runtime deobfuscation control for FG2-era FML
 * ({@link xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2#PROP_RUNTIME_DEOBF}).
 *
 * <p>FG2-era {@code FMLDeobfTweaker} unconditionally installs FML's
 * {@code DeobfuscationTransformer} when a deobf environment is
 * detected. In a unimined dev environment the jar is already
 * deobfuscated to dev names, so the transformer only gets in the way
 * — 1.x nulled it out entirely. The transformed class instead asks
 * {@link xyz.wagyourtail.unimined.transform.forge.internal.fg2.rt.UniminedFg2}
 * at runtime, so {@code -Dunimined.fg2.runtimeDeobf} re-enables it
 * without regenerating the jar.
 */
package xyz.wagyourtail.unimined.transform.forge.internal.fg2.deobf;
