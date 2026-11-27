package xyz.wagyourtail.unimined.minecraft

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.DomainObjectSet
import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.api.util.delegatingTo
import java.io.File
import javax.inject.Inject

/**
 * One named mod classpath group — the `mods { "<id>" { } }` member.
 * The name should match the mod id. Groups declare which source-set
 * outputs and extra files constitute one logical mod on a dev launch,
 * so loaders that group classpath entries by mod can attribute them:
 * fabric-family runs serialize the selected groups into the
 * `fabric.classPathGroups` system property (fabric-loader #585 —
 * groups separated by a doubled path separator, entries by a single
 * one); forge-family runs append `id%%dir` entries to the
 * `MOD_CLASSES` environment variable (Loom `loom.mods` / NFRT
 * `modClasses` parity).
 */
abstract class ModGroupSpec @Inject constructor(
    private val name: String,
    val project: Project,
    objects: ObjectFactory,
) : Named {

    /** Source sets whose `output` dirs make up this mod's classes. */
    val sourceSets: DomainObjectSet<SourceSet> =
        objects.domainObjectSet(SourceSet::class.java)

    /** Extra classpath dirs/jars attributed to this mod — Loom
     *  `ModSettings.modFiles` parity. */
    val modFiles: ConfigurableFileCollection = objects.fileCollection()

    override fun getName(): String = name

    /** Add a source set by name from this environment's project. */
    fun sourceSet(name: String) {
        val container = project.extensions
            .getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
        sourceSets.add(container.findByName(name)
            ?: throw org.gradle.api.GradleException(
                "mods { \"${this.name}\" }: source set '$name' does not " +
                    "exist in project ${project.path}"))
    }

    /** Add a source set object — may belong to another project. */
    fun sourceSet(sourceSet: SourceSet) {
        sourceSets.add(sourceSet)
    }

    /** Add classpath dirs/jars to this mod group. */
    fun modFiles(vararg paths: Any) {
        modFiles.from(*paths)
    }

    /** `modFiles { }` configure twin. */
    fun modFiles(action: Action<in ConfigurableFileCollection>) {
        action.execute(modFiles)
    }

    /** Groovy DSL twin of [modFiles]. */
    fun modFiles(
        @DelegatesTo(value = ConfigurableFileCollection::class,
            strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = action.delegatingTo(modFiles)

    /** Every classpath file this group contributes — each source set's
     *  classes + resources output dirs plus [modFiles]. Resolved
     *  lazily at launch wiring / execution. */
    fun launchFiles(): List<File> =
        sourceSets.flatMap {
            it.output.classesDirs.files + listOfNotNull(it.output.resourcesDir)
        } + modFiles.files

    /** The group's outputs as classpath contributors — each source set
     *  contributes its `output` file collection (carrying the
     *  compile/processResources `builtBy`), so file-consuming task
     *  inputs pick up the producing-task dependency; raw dirs alone
     *  trip Gradle's implicit-dependency validation. `modFiles` is
     *  wrapped in a list deliberately: `ConfigurableFileCollection` is
     *  itself iterable, so Kotlin's `list + collection` overload would
     *  flatten it into plain files and discard its builtBy producers. */
    fun launchOutputs(): List<Any> =
        sourceSets.map { it.output } + listOf(modFiles)
}
