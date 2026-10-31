import org.gradle.api.tasks.testing.logging.TestLogEvent
import xyz.wagyourtail.commons.gradle.GradleProjectExtension

plugins {
    id("xyz.wagyourtail.commons-gradle") version "1.0.5-SNAPSHOT"
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("org.jetbrains.dokka") version "2.2.0"
}

subprojects {
    // mc-launcher is a Gradle-free KMP library — exempt from the
    // kotlin-jvm/java/commons conventions; it sets its own coords
    if (name == "mc-launcher") return@subprojects

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "maven-publish")
    apply(plugin = "xyz.wagyourtail.commons-gradle")
    apply(plugin = "org.jetbrains.dokka")

    extensions.configure<GradleProjectExtension>("commons") {
        autoGroup()
        autoVersion()
    }

    val publishedName = if (name.startsWith("${rootProject.name}-")) name else "${rootProject.name}-$name"

    extensions.configure<BasePluginExtension>("base") {
        archivesName.set(publishedName)
    }

    val javaVersion = 17

    extensions.configure<JavaPluginExtension>("java") {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(javaVersion))
        }
        withSourcesJar()
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        jvmToolchain(javaVersion)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events.addAll(listOf(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED))
        }
    }

    // core/gradle split: `core` = Gradle-free pipeline code, `main`
    // = Gradle adapters. `test` retargeted to core-only (enforced
    // pure); `testGradle` = adapter/TestKit tests. See
    // Docs/progress-gradle-split.md.
    extensions.configure<JavaPluginExtension>("java") {
        val core = sourceSets.create("core")
        sourceSets.named("main") {
            compileClasspath += core.output
            runtimeClasspath += core.output
            // expose core classes as part of main's output — jar,
            // apiElements/runtimeElements, and project consumers
            // see one artifact
            output.dir(mapOf("builtBy" to "compileCoreKotlin"), "build/classes/kotlin/core")
            output.dir(mapOf("builtBy" to "compileCoreJava"), "build/classes/java/core")
            output.dir(mapOf("builtBy" to "processCoreResources"), "build/resources/core")
        }
        val testPureCompile = configurations.create("testPureCompileClasspath") {
            isCanBeConsumed = false
            isCanBeResolved = true
            extendsFrom(configurations["testImplementation"], configurations["testCompileOnly"])
        }
        val testPureRuntime = configurations.create("testPureRuntimeClasspath") {
            isCanBeConsumed = false
            isCanBeResolved = true
            extendsFrom(configurations["testImplementation"], configurations["testRuntimeOnly"])
        }
        sourceSets.named("test") {
            compileClasspath = files(core.output) + testPureCompile
            runtimeClasspath = files(core.output, output) + testPureRuntime
        }
        val testGradleSS = sourceSets.create("testGradle") {
            compileClasspath += core.output +
                    sourceSets["main"].output + sourceSets["test"].output
            runtimeClasspath += core.output +
                    sourceSets["main"].output + sourceSets["test"].output
        }
        configurations.named("implementation") {
            extendsFrom(configurations["coreImplementation"])
        }
        configurations.named("api") {
            extendsFrom(configurations["coreApi"])
        }
        // testGradle inherits test's dependencies
        configurations.named("testGradleImplementation") {
            extendsFrom(configurations["testImplementation"])
        }
        configurations.named("testGradleRuntimeOnly") {
            extendsFrom(configurations["testRuntimeOnly"])
        }
        configurations.named("testGradleCompileOnly") {
            extendsFrom(configurations["testCompileOnly"])
        }
        // project consumers resolve apiElements/runtimeElements'
        // "classes" secondary variant, which snapshots main's dirs
        // before output.dir registers — publish core dirs explicitly
        listOf("apiElements", "runtimeElements").forEach { conf ->
            configurations.matching { it.name == conf }.all {
                outgoing.variants.matching { it.name == "classes" }.all {
                    // explicit artifact() calls REPLACE the variant's
                    // auto-derived list — main dirs must be re-registered
                    // or downstream project deps stop seeing main classes
                    artifact(layout.buildDirectory.dir("classes/kotlin/main")) {
                        type = "java-classes-directory"
                        builtBy("compileKotlin")
                    }
                    artifact(layout.buildDirectory.dir("classes/java/main")) {
                        type = "java-classes-directory"
                        builtBy("compileJava")
                    }
                    artifact(layout.buildDirectory.dir("classes/kotlin/core")) {
                        type = "java-classes-directory"
                        builtBy("compileCoreKotlin")
                    }
                    artifact(layout.buildDirectory.dir("classes/java/core")) {
                        type = "java-classes-directory"
                        builtBy("compileCoreJava")
                    }
                }
            }
        }
        val testGradleTask = tasks.register<Test>("testGradle") {
            testClassesDirs = testGradleSS.output.classesDirs
            classpath = testGradleSS.runtimeClasspath
            tasks.findByName("pluginUnderTestMetadata")?.let { metadata ->
                classpath += files({
                    (metadata as org.gradle.plugin.devel.tasks.PluginUnderTestMetadata)
                        .outputDirectory
                })
                dependsOn(metadata)
            }
            useJUnitPlatform()
        }
        tasks.named("check") {
            dependsOn(testGradleTask)
        }
        // testGradle sees main's internals (same-module tests);
        // test sees core's internals
        // main sees core's internals — they're one published module
        tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
            friendPaths.from(tasks.named("compileCoreKotlin").map { it.outputs.files })
        }
        tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestGradleKotlin") {
            // testGradle friends BOTH core and main — friend access to
            // internals is not transitive through main
            friendPaths.from(tasks.named("compileKotlin").map { it.outputs.files })
            friendPaths.from(tasks.named("compileCoreKotlin").map { it.outputs.files })
            libraries.from(core.output, sourceSets["main"].output, sourceSets["test"].output)
        }
        tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
            friendPaths.from(tasks.named("compileCoreKotlin").map { it.outputs.files })
            libraries.from(core.output)
        }
    }
    dependencies.add("testGradleImplementation", dependencies.gradleApi())

    extensions.configure<PublishingExtension>("publishing") {
        publications.withType<MavenPublication>().configureEach {
            if (artifactId == project.name) {
                artifactId = publishedName
            }
        }
        repositories {
            maven {
                name = "WagYourMaven"
                url = uri(
                    "https://maven.wagyourtail.xyz/" +
                            if (project.hasProperty("version_snapshot")) "snapshots/" else "releases/"
                )
                credentials {
                    username = (findProperty("mvn.user") as String?)
                        ?: providers.environmentVariable("USERNAME").orNull
                    password = (findProperty("mvn.key") as String?)
                        ?: providers.environmentVariable("TOKEN").orNull
                }
            }
        }
    }
}

// aggregated API docs — `:dokkaGenerate` at the root produces
// build/dokka/ (copied into docs/api/ by the docs workflow)
dokka {
    moduleName.set("unimined")
}

dependencies {
    subprojects.forEach {
        // mc-launcher doesn't apply the dokka plugin (see subprojects block)
        if (it.name != "mc-launcher") dokka(project(it.path))
    }
}

// `unimined-templates` artifact (plan §13) — packaged testing/
// projects CI attaches to releases; init frontends consume richer
// templates from the zip than the built-in minimal emit
tasks.register<Zip>("packageTemplates") {
    group = "unimined"
    description = "Packages testing/ projects as the unimined-templates artifact"
    archiveFileName.set("unimined-templates.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("testing") {
        exclude("**/build/**", "**/run/**", "**/.gradle/**",
                "**/remappedSrc/**", "**/.idea/**")
        into("testing")
    }
}
