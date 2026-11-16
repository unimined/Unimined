package xyz.wagyourtail.unimined.minecraft.localrepo

import xyz.wagyourtail.commonskt.jvm.copyAtomicTo
import xyz.wagyourtail.unimined.api.util.writeAtomically
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
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

    /** The maven-layout module dir — `<repo>/<group-path>/<artifactId>/<version>/`. */
    fun moduleDir(
        repoDir: Path,
        group: String,
        artifactId: String,
        version: String
    ): Path = repoDir
        .resolve(group.replace('.', '/'))
        .resolve(artifactId)
        .resolve(version)

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
        val moduleDir = moduleDir(repoDir, group, artifactId, version)
            .createDirectories()

        // Content-gated writes: this repo dir is a real maven repo to
        // Gradle, so the configuration cache fingerprints its entries
        // as inputs. Rewriting byte-identical files would still route
        // through the atomic-write's transient `.*.tmp` sibling — a
        // file that appears in the fingerprinted listing during one
        // store pass and is gone the next, making the entry
        // permanently unreusable.
        val pom = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>$group</groupId>
                <artifactId>$artifactId</artifactId>
                <version>$version</version>
                <packaging>jar</packaging>
            </project>
            """.trimIndent()
        val pomFile = moduleDir.resolve("$artifactId-$version.pom")
        if (Files.notExists(pomFile) || pomFile.readText() != pom) {
            pomFile.writeAtomically { tmp -> tmp.writeText(pom) }
        }

        for ((classifier, file) in files) {
            val name = buildString {
                append("$artifactId-$version")
                if (classifier.isNotEmpty()) append("-$classifier")
                append(".jar")
            }
            val dest = moduleDir.resolve(name)
            if (Files.notExists(dest) || Files.mismatch(dest, file) != -1L) {
                file.copyAtomicTo(dest)
            }
        }
        return moduleDir
    }

}
