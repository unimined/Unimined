package xyz.wagyourtail.unimined.remap

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

/**
 * One resolved `include`-bucket artifact bound for the production jar's
 * jar-in-jar entries — the file plus its loader-facing identity.
 * Resolved lazily from `include.incoming.artifacts` by environment
 * wiring; consumed by `RemapJarTask`/the identity `Jar` post-pass.
 */
abstract class NestedJarEntrySpec {

    /** The jar to embed — verbatim or per the `nestedJars` recompress
     *  policy. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jar: RegularFileProperty

    /** `group:artifact:version[:classifier][@ext]` — from
     *  [xyz.wagyourtail.unimined.api.util.getCoords]; absent for file
     *  dependencies without resolvable coordinates. */
    @get:Optional
    @get:Input
    abstract val coords: Property<String>

    /** jarjar `version.range` — the declared dependency's version
     *  constraint when it is a range; resolved version otherwise. */
    @get:Optional
    @get:Input
    abstract val versionRange: Property<String>

    /** `FMLModType` manifest attribute stamped into the nested jar
     *  (`MOD`/`LIBRARY`/`GAMELIBRARY`/`LANGPROVIDER`/`CUSTOM`) —
     *  declares the jar's FML layer + scan type without requiring
     *  `mods.toml` inside it. `LIBRARY`/`LANGPROVIDER` land in the
     *  PLUGIN layer (langprovider-visible); `MOD`/`GAMELIBRARY` in
     *  GAME. Unset embeds as-is — FML defaults nested jars to
     *  `GAMELIBRARY`. jarjar format only; forces the inner rewrite. */
    @get:Optional
    @get:Input
    abstract val fmlModType: Property<String>

    /** jarjar `isObfuscated` metadata override — absent → detected
     *  from the nested jar's `Obfuscated-By` manifest attribute. Marks
     *  jars carrying obf-mapped names that FML must runtime-remap. */
    @get:Optional
    @get:Input
    abstract val obfuscated: Property<Boolean>

}
