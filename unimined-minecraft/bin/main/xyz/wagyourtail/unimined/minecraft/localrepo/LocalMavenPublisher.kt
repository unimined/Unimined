package xyz.wagyourtail.unimined.minecraft.localrepo

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Publishes locally-produced artifacts (transformed MC jars, merged dev
 * jars — anything with no upstream module id) into a maven-layout
 * directory under synthetic coordinates, so downstream consumption is a
 * real `g:a:v` dependency (IDEA shows it as a maven library with
 * attachable sources).
 *
 * Synthetic coords convention: `unimined.generated:<artifact>:<version>`
 * with the transform target encoded on the artifactId or classifier axis
 * (`minecraft-fabric` / classifier `fabric`) — multi-target remaps must
 * differ in coordinates, not just attributes.
 */
object LocalMavenPublisher {

    /**
     * Write POM + artifacts into `<repo>/<group-path>/<artifactId>/<version>/`.
     *
     * @param files classifier-or-empty → artifact file; the `""` entry is
     *  the main jar, `"sources"` the `-sources` classifier, etc.
     * @return the module dir written
     */
    fun publish(
        repoDir: Path,
        group: String,
        artifactId: String,
        version: String,
        files: Map<String, Path>
    ): Path {
        val moduleDir = repoDir
            .resolve(group.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version)
            .createDirectories()

        moduleDir.resolve("$artifactId-$version.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>$group</groupId>
                <artifactId>$artifactId</artifactId>
                <version>$version</version>
                <packaging>jar</packaging>
            </project>
            """.trimIndent()
        )

        for ((classifier, file) in files) {
            val name = buildString {
                append("$artifactId-$version")
                if (classifier.isNotEmpty()) append("-$classifier")
                append(".jar")
            }
            java.nio.file.Files.copy(
                file, moduleDir.resolve(name),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }
        return moduleDir
    }

}
