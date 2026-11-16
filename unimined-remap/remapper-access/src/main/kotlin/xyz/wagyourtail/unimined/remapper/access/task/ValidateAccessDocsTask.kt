package xyz.wagyourtail.unimined.remapper.access.task

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import xyz.wagyourtail.unimined.api.mapping.SerializedMappingData
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.remapper.access.ApplyClassTweakerStepType

/**
 * `validate<Env>Access` — loom `ValidateAccessWidenerTask` parity:
 * validates the environment's own access documents' declared targets
 * against the dev-namespace Minecraft artifact and the mapping tree,
 * without running a remap. Shares `access { validate }`'s matching
 * semantics via [ApplyClassTweakerStepType.validateDocs]; dep-declared
 * and coordinate-consumed docs are excluded by construction (their
 * targets are the dependency's responsibility).
 */
@DisableCachingByDefault(
    because = "a check, not a transform — the output is a verdict, " +
            "and re-running on changed docs must not be skipped"
)
abstract class ValidateAccessDocsTask : DefaultTask() {

    /** The environment's own access documents (headerless docs bind to
     *  [headerlessNamespace]). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val accessDocs: ConfigurableFileCollection

    /** Namespace headerless docs' names are in — the env's dev
     *  namespace (own declarations are dev-authored). */
    @get:Optional
    @get:Input
    abstract val headerlessNamespace: Property<String>

    /** Namespace the validated jar's classes are in — the dev
     *  namespace of the Minecraft artifact under test. */
    @get:Input
    abstract val targetNamespace: Property<String>

    /** Header-namespace → tree-namespace aliases for dep-family
     *  conventions (empty for own docs). */
    @get:Input
    abstract val namespaceAliases: MapProperty<String, String>

    /** Serialized mapping bundle ([SerializedMappingData] zip or
     *  row-bundle JSON) — must expose a [MappingTree] view. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: RegularFileProperty

    /** The dev-namespace Minecraft artifact the declared targets are
     *  checked against. */
    @get:InputFile
    @get:Classpath
    abstract val minecraftJar: RegularFileProperty

    @TaskAction
    fun validate() {
        val unmatched = ApplyClassTweakerStepType().validateDocs(
            accessDocs.files.map { it.name to it },
            headerlessNamespace.orNull?.let { mapOf("source" to it) }
                ?: emptyMap(),
            SerializedMappingData.readEither(
                mappingFile.get().asFile.toPath()
            ).view<MappingTree>() ?: throw GradleException(
                "validate access docs: the mapping bundle exposes no " +
                        "MappingTree view — the environment's mapping " +
                        "data must serialize as a tree"
            ),
            targetNamespace.get(),
            namespaceAliases.getOrElse(emptyMap()),
            minecraftJar.get().asFile.toPath()
        )
        if (unmatched.isEmpty()) return
        val lines = unmatched.take(20).joinToString("\n") { (src, t) ->
            "  $src: $t"
        }
        throw GradleException(
            "access doc validation failed — ${unmatched.size} declared " +
                    "access rule(s) matched nothing:\n$lines" +
                    (if (unmatched.size > 20)
                        "\n  ... and ${unmatched.size - 20} more" else "")
        )
    }
}
