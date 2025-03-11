import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
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
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get().toInt()))
    }
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
}

repositories {
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
    implementation(libs.access.widener)

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
            "Implementation-Version" to project.version
        )
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    val source by sourceSets.getting

    from(
        api.allSource,
        minecraft.allSource,
        mapping.allSource,
        source.allSource,
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
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group as String
            artifactId = project.properties["archives_base_name"] as String? ?: project.name
            version = project.version as String

            artifact(sourcesJar) {
                classifier = "sources"
            }
        }
    }
}

// A task to output a json file with a list of all the test to run
val writeActionsTestMatrix by tasks.registering {
    doLast {
        val testMatrix = arrayListOf<String>()

        file("src/test/kotlin/xyz/wagyourtail/unimined/test/integration").listFiles()?.forEach {
            if (it.name.endsWith("Test.kt")) {

                val className = it.name.replace(".kt", "")
                testMatrix.add("xyz.wagyourtail.unimined.test.integration.${className}")
            }
        }

        testMatrix.add("xyz.wagyourtail.unimined.api.mappings.*")

        testMatrix.add("xyz.wagyourtail.unimined.util.*")

        val json = groovy.json.JsonOutput.toJson(testMatrix)
        val output = file("build/test_matrix.json")
        output.parentFile.mkdir()
        output.writeText(json)
    }
}

/**
 * Replaces invalid characters in test names for GitHub Actions artifacts.
 */
abstract class PrintActionsTestName : DefaultTask() {
    @get:Input
    @get:Option(option = "name", description = "The test name")
    abstract val testName: Property<String>;

    @TaskAction
    fun run() {
        val sanitised = testName.get().replace('*', '_')
        File(System.getenv()["GITHUB_OUTPUT"]).writeText("\ntest=$sanitised")
    }
}

tasks.register<PrintActionsTestName>("printActionsTestName") {}
