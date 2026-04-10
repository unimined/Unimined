package xyz.wagyourtail.unimined.util

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.util.GradleVersion
import org.junit.jupiter.params.provider.Arguments
import java.io.File
import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.writeText

val GRADLE_CURRENT: String = GradleVersion.current().version

fun runTestProject(name: String, gradle: String = GRADLE_CURRENT): BuildResult {
	return runGradle(getTestProjectPath(name), gradle)
}

fun getTestProjectPath(name: String): Path {
	return Paths.get(".").resolve("testing").resolve(name)
}

fun openZipFileSystem(project: String, path: String): FileSystem? {
	val fullPath = getTestProjectPath(project).resolve(path)

	if (!fullPath.exists()) return null

	return fullPath.openZipFileSystem(mapOf("mutable" to false))
}

object IntegrationTestUtils {
	/**
	 * Returns an array of unique Gradle versions to test with.
	 * - It should always contain the version currently in use, for basic support.
	 * - It should always contain the LTS target as the minimum version, for legacy support.
	 * - It should contain the latest patches of each minor of the latest major version as well,
	 * for incremental testing.
	 *
	 * TL;DR: ***current*** + `previous.latest.latest` + `latest.each.latest`.
	 */
	@JvmStatic
	val gradleVersions = setOf(
		GRADLE_CURRENT, // Always test the current version
		"8.14.4", // minimum version: 8.x latest
		"9.0.0", "9.1.0", "9.2.1", "9.3.1", "9.4.1" // Incrementally test 9.x
	).toTypedArray()

	@JvmStatic
	fun versionMatrix(vararg gameVersions: String): Array<Arguments> = buildList {
		for (gradle in gradleVersions) for (game in gameVersions)
			add(Arguments.of(gradle, game))
	}.toTypedArray()
}

fun runGradle(dir: Path, version: String = GRADLE_CURRENT): BuildResult {
	println("Running gradle in $dir")
	val buildDir = dir.resolve("build")
	if (buildDir.exists()) buildDir.deleteRecursively()

	val settings = dir.resolve("settings.gradle")
	if (settings.exists()) {
		val lines = settings.toFile().readLines().toMutableList()
		val includeBuild = lines.indexOfFirst { it.startsWith("// includeBuild") }
		if (includeBuild != -1) {
			lines[includeBuild] = lines[includeBuild].substring(3)
			settings.writeText(lines.joinToString("\n"))
		}
	}

	val uniminedDir = dir.resolve(".gradle").resolve("unimined")
	if (uniminedDir.exists()) uniminedDir.deleteRecursively()

	val classpath = System.getProperty("java.class.path").split(File.pathSeparatorChar).map { File(it) }
	val result = GradleRunner.create()
		.withGradleVersion(version)
		.withProjectDir(dir.toFile())
		.withArguments("clean", "build", "--stacktrace", "--info", "-Punimined.forceReload=true")
		.withPluginClasspath(classpath)
		.build()
	if (settings.exists()) {
		val lines = settings.toFile().readLines().toMutableList()
		val includeBuild = lines.indexOfFirst { it.startsWith("// includeBuild") }
		if (includeBuild != -1) {
			lines[includeBuild] = lines[includeBuild].substring(3)
			settings.writeText(lines.joinToString("\n"))
		}
	}
	return result
}

fun createFakeProject(): Project {
	val project = ProjectBuilder.builder().build()
	project.pluginManager.apply("xyz.wagyourtail.unimined")
	return project
}
