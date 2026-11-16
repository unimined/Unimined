package xyz.wagyourtail.unimined.ide

/**
 * Gradle-free model shared by the IDE emitters — the resolved shape
 * of one launchable run plus the classpath groups an IDE needs to
 * show. Produced lazily at task-execution time from the same spec
 * the `run<Name>` tasks consume (RunsWiring's
 * `MutableRunConfigSpec` + provider launch metadata); emitters
 * (IDEA/VSCode/Eclipse) render this, never re-resolve it.
 */

/** Classpath groupings an IDE can display — "classpath groups" in
 *  1.x terms. */
enum class ClasspathGroup(val displayName: String) {

    /** The Minecraft artifact itself (dev-namespace view for dev
     *  runs, raw for prod). */
    MINECRAFT("Minecraft"),

    /** The loader product's jars (foxloader/fabric/etc.). */
    LOADER("Loader"),

    /** Declared libraries of the version document. */
    LIBRARIES("Libraries"),

    /** Mod jars on the classpath — dev `*Direct` views or prod
     *  `*Remapped` views per run. */
    MODS("Mods"),

    /** The project's own compiled output. */
    PROJECT("Project"),
}

/** One classpath entry — a jar (or classes dir) with optional
 *  attached sources for IDE navigation. */
data class ClasspathEntry(
    val jar: String,
    val sources: String? = null,
    val group: ClasspathGroup,
)

/** A fully-resolved run the IDE emitters render. Field names mirror
 *  `RunConfig` so the producer maps spec→model 1:1. */
data class IdeRunModel(
    /** IDE display/file name — `RunConfig.ideName` (defaults to the
     *  run name). */
    val name: String,

    /** Optional IDE folder/group label — `RunConfig.ideFolderName`;
     *  emitters that support config grouping honor it. */
    val folderName: String? = null,

    /** `RunConfig.singleInstance` — emitters that model a
     *  "single instance only" flag honor it (IDEA `singleton`). */
    val singleInstance: Boolean = false,

    /** Environment sourceSet id (`main`, ...). */
    val environmentId: String,

    val side: String,
    val prod: Boolean,
    val mainClass: String,
    val jvmArgs: List<String>,
    val programArgs: List<String>,
    val environmentVariables: Map<String, String>,
    val workingDirectory: String,
    val classpath: List<ClasspathEntry>,
)

/** Per-environment library groups for IDE sync — what "classpath
 *  groups" concretely means: named bundles the IDE shows under the
 *  module's External Libraries. */
data class IdeClasspathModel(
    val environmentId: String,

    /** BCP 47 / IntelliJ language level string per env (legacy MC
     *  targets 8). */
    val languageLevel: String? = null,

    val groups: Map<ClasspathGroup, List<ClasspathEntry>>,

    /** Dirs the IDE must not index (run/, natives, .gradle/unimined). */
    val excludedDirectories: List<String>,
)
