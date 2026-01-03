import org.eclipse.jgit.api.Git
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

buildscript {
	dependencies {
		classpath(libs.jgit)
	}
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    `java-gradle-plugin`
    `maven-publish`
}

version = if (project.hasProperty("version_snapshot")) project.properties["version"] as String + "-SNAPSHOT" else project.properties["version"] as String
group = project.properties["maven_group"] as String

base {
    archivesName.set(project.properties["archives_base_name"] as String)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.wagyourtail.xyz/releases")
    maven("https://maven.wagyourtail.xyz/snapshots")
    maven("https://maven.neoforged.net/releases")
    maven("https://maven.minecraftforge.net/")
    maven("https://maven.fabricmc.net/")
    gradlePluginPortal()
}

fun SourceSet.inputOf(vararg sourceSets: SourceSet) {
    for (sourceSet in sourceSets) {
        compileClasspath += sourceSet.compileClasspath
        runtimeClasspath += sourceSet.runtimeClasspath
    }
}

fun SourceSet.outputOf(vararg sourceSets: SourceSet) {
    for (sourceSet in sourceSets) {
        compileClasspath += sourceSet.output
        runtimeClasspath += sourceSet.output
    }
}

val api by sourceSets.creating {
    inputOf(sourceSets.main.get())
}

val mapping by sourceSets.creating {
    inputOf(sourceSets.main.get())
    outputOf(api)
}

val source by sourceSets.creating {
    inputOf(sourceSets.main.get())
    outputOf(mapping, api)
}

val mods by sourceSets.creating {
    inputOf(sourceSets.main.get())
    outputOf(api, mapping, source)
}

val runs by sourceSets.creating {
    inputOf(sourceSets.main.get())
    outputOf(api)
}

val minecraft by sourceSets.creating {
    inputOf(sourceSets.main.get())
    outputOf(api, mapping, mods, runs, source)
}

val main by sourceSets.getting {
    outputOf(api, mapping, source, mods, runs, minecraft)
}

val test by sourceSets.getting {
    inputOf(sourceSets.main.get())
    outputOf(api, mapping, source, mods, runs, minecraft)
}

dependencies {
    runtimeOnly(gradleApi())
    implementation(kotlin("metadata-jvm"))
    implementation(libs.jb.annotations)

    implementation(libs.guava)
    implementation(libs.gson)

    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.asm.tree)
    implementation(libs.asm.analysis)
    implementation(libs.asm.util)

    implementation(libs.unimined.mapping.library.jvm)
    implementation(libs.tiny.remapper) {
        exclude(group = "org.ow2.asm")
    }

    implementation(libs.binarypatcher) {
        exclude(mapOf("group" to "commons-io"))
    }
    implementation(libs.jbsdiff)

    implementation(libs.classtweaker)

    implementation(libs.commons.io)
    implementation(libs.commons.compress)

    implementation(libs.jgit)

    implementation(libs.java.keyring)
    implementation(libs.minecraftauth)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.jar {
    from(
        sourceSets["api"].output,
        sourceSets["mapping"].output,
        sourceSets["source"].output,
        sourceSets["mods"].output,
        sourceSets["runs"].output,
        sourceSets["minecraft"].output,
        sourceSets["main"].output
    )

    manifest {
        attributes(
            "Implementation-Version" to if (project.hasProperty("version_snapshot")) {
                buildString {
                    append(project.version.toString().removeSuffix("-SNAPSHOT"))
                    append("-")
                    append(Git.open(rootDir).repository.resolve("HEAD").abbreviate(7).name().trim())
                    append("-SNAPSHOT")
                }
            } else project.version
        )
    }
}

val sourcesJar by tasks.getting(Jar::class) {
    from(
        api.allSource,
        minecraft.allSource,
        mapping.allSource,
        sourceSets["source"].allSource,
        mods.allSource,
        runs.allSource,
        main.allSource
    )
}

tasks.build {
    dependsOn(sourcesJar)
}

tasks.test {
    useJUnitPlatform()

    testLogging {
        events.add(TestLogEvent.PASSED)
        events.add(TestLogEvent.SKIPPED)
        events.add(TestLogEvent.FAILED)
    }
}

tasks.dokkaGenerate {
	doFirst {
		file("Writerside/v.list").writeText(
			"""
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE vars SYSTEM "https://resources.jetbrains.com/writerside/1.0/vars.dtd">
                <vars>
                    <var name="version" value="${project.version}"/>
                </vars>
            """.trimIndent()
		)
	}
}

dokka {
	moduleName.set(project.displayName)
	dokkaSourceSets.main {
		suppress = true
	}
	dokkaSourceSets.named("api") {
		suppress = false
	}

	dokkaPublications.html {
		outputDirectory.set(projectDir.resolve("docs/api-docs/"))
	}
}

gradlePlugin {
    plugins {
        create("simplePlugin") {
            id = "xyz.wagyourtail.unimined"
            implementationClass = "xyz.wagyourtail.unimined.UniminedPlugin"
        }
    }
}

publishing {
    repositories {
        maven {
            name = "WagYourMaven"
            url = uri("https://maven.wagyourtail.xyz/" + if (project.hasProperty("version_snapshot")) "snapshots/" else "releases/")
            credentials {
                username = project.findProperty("mvn.user") as String? ?: System.getenv("USERNAME")
                password = project.findProperty("mvn.key") as String? ?: System.getenv("TOKEN")
            }
        }
    }
}

// A task to output a json file with a list of all the test to run
val writeActionsTestMatrix by tasks.registering {
    doLast {
        val testMatrix = arrayListOf<Map<String, String>>()

        val broken = setOf<String>()

        file("src/test/kotlin/xyz/wagyourtail/unimined/test/integration").listFiles()?.forEach {
            if (it.name.endsWith("Test.kt") && !broken.contains(it.name)) {
                val testName = it.name.replace(".kt", "")
                val testPath = "xyz.wagyourtail.unimined.test.integration.${testName}"
                testMatrix.add(mapOf(
                    "name" to formatTestName(testName),
                    "path" to testPath
                ))
            }
        }

        testMatrix.add(mapOf(
            "name" to "Util",
            "path" to "xyz.wagyourtail.unimined.util.*"
        ))

        val json = groovy.json.JsonOutput.toJson(testMatrix.sortedBy { it["name"] })
        val output = file("build/test_matrix.json")
        output.parentFile.mkdir()
        output.writeText(json)
    }
}

fun formatTestName(name: String): String {
    val testName = name.removeSuffix("Test")
    val index = testName.indexOfFirst { it.isDigit() }
    return if (index != -1) {
        val loader = testName.take(index)
        val version = testName.substring(index).replace("_", ".")
        "$loader $version"
    } else {
       testName
    }
}