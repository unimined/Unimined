package xyz.wagyourtail.unimined.publish.internal

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import xyz.wagyourtail.unimined.publish.PublishExtension

/**
 * Maven publication wiring for the publish host (formerly the
 * `unimined-publish-maven` adapter — folded in per plan §11).
 *
 * One `MavenPublication` per `unimined<Env>` adhoc component means one
 * Maven module per loader environment — classifiers are not used for
 * env separation because each module needs its own POM/dependency
 * list. `artifactId` defaults to the project name for `main` and
 * `<project>-<env>` for other environments (the `name-fabric`/
 * `name-forge` convention); explicit
 * `environment("<env>") { artifactId = ... }` always wins. Publications
 * that would resolve to identical coordinates fail the build.
 *
 * The merged loader artifact (`adapter-forgix`'s `mergedElements`) is
 * never routed here — it is a ModPublisher-path artifact only.
 */
internal object MavenPublicationWiring {

    /**
     * Create the `MavenPublication` for one `unimined<Env>` component.
     * Returns the publication, or null when the component isn't an
     * env-bound publish component (foreign adhoc components named
     * `unimined*` stay untouched).
     */
    fun addPublication(
        project: Project,
        ext: PublishExtension,
        comp: AdhocComponentWithVariants
    ): MavenPublication? {
        val name = comp.name
        if (!name.startsWith("unimined")) return null
        // uniminedMain -> main, uniminedFabric -> fabric
        val env = name.removePrefix("unimined")
            .replaceFirstChar(Char::lowercase)
        val envPub = ext.environments[env] ?: return null

        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        return publishing.publications.maybeCreate(name, MavenPublication::class.java)
            .apply {
                from(comp)
                envPub.groupId?.let { groupId = it }
                artifactId = envPub.artifactId ?: defaultArtifactId(project, env)
                envPub.version?.let { version = it }
            }
    }

    /**
     * Create the `java` MavenPublication for `components.java` — the
     * `javaPublication()` opt-in (plan §11: never automatic). Runs in
     * `afterEvaluate` ahead of user `afterEvaluate` blocks so
     * `publishing.publications.named("java") { }` customization
     * resolves. `artifactId` defaults to `<project>-java` — `uniminedMain`
     * already claims `<project>`; override via the publication DSL.
     */
    fun addJavaPublication(project: Project) {
        val java = project.components.findByName("java") ?: return
        project.extensions.getByType(PublishingExtension::class.java)
            .publications.maybeCreate("java", MavenPublication::class.java)
            .apply {
                from(java)
                artifactId = "${project.name}-java"
            }
    }

    /**
     * Post-DSL finishing: the cross-publication coordinate check over
     * every `unimined*` publication plus `java` when opted in. The
     * `java` publication itself is created earlier by
     * [addJavaPublication] so users can customize it.
     */
    fun finish(project: Project, ext: PublishExtension) {
        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        val candidates = publishing.publications
            .withType(MavenPublication::class.java)
            .filter { pub ->
                pub.name.startsWith("unimined") ||
                        (ext.javaPublication.get() &&
                                ext.mavenPublications.get() && pub.name == "java")
            }
        checkUniqueCoordinates(project, candidates)
    }

    /** `<project>` for `main`, `<project>-<env>` otherwise. */
    internal fun defaultArtifactId(project: Project, env: String): String =
        if (env == "main") project.name else "${project.name}-$env"

    /** Effective (groupId, artifactId, version) of a publication. */
    private fun coordinatesOf(
        project: Project, publication: MavenPublication
    ): Triple<String, String, String> = Triple(
        publication.groupId ?: project.group.toString(),
        publication.artifactId ?: project.name,
        publication.version ?: project.version.toString()
    )

    /**
     * Fail when two publications resolve to the same Maven coordinates —
     * without distinct `artifactId`s per environment they would publish
     * to the same module path and overwrite each other.
     */
    internal fun checkUniqueCoordinates(
        project: Project, publications: Collection<MavenPublication>
    ) {
        val dupes = publications.groupBy { coordinatesOf(project, it) }
            .filterValues { it.size > 1 }
        if (dupes.isEmpty()) return
        val detail = dupes.entries.joinToString("; ") { (coord, pubs) ->
            "${pubs.joinToString(", ") { it.name }} -> " +
                "${coord.first}:${coord.second}:${coord.third}"
        }
        throw GradleException(
            "unimined-publish: multiple publications resolve to the " +
                "same coordinates ($detail). Set a distinct artifactId per " +
                "environment via uniminedPublish { environment(\"<env>\") { " +
                "artifactId = \"...\" } }"
        )
    }

}
