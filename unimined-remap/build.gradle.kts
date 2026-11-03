plugins {
    `java-gradle-plugin`
    `java-test-fixtures`
}


dependencies {
    "coreApi"(project(":unimined-api"))
    "coreApi"(project(":unimined-api"))
    "coreApi"(libs.unimined.mapping.library)
    "coreApi"(libs.commons.core)
    "coreApi"(libs.commons.kt)
    "testFixturesApi"(libs.unimined.mapping.library)
    "testFixturesApi"(libs.asm)
    api(project(":unimined-api"))
    implementation(libs.commons.gradle)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // functional test exercises the real engine through remappedView()
    testImplementation(project(":unimined-remap:remapper-tiny"))

    "testGradleImplementation"(gradleTestKit())
    "testGradleImplementation"(libs.junit.jupiter)
    "testGradleRuntimeOnly"(libs.junit.platform.launcher)
    "testGradleImplementation"(project(":unimined-remap:remapper-tiny"))
}

gradlePlugin {
    plugins {
        create("remap") {
            id = "xyz.wagyourtail.unimined.remap"
            implementationClass = "xyz.wagyourtail.unimined.remap.GradlePlugin"
        }
    }
}

// testFixtures compile sees core output (SPI fakes) + internals
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestFixturesKotlin") {
    friendPaths.from(tasks.named("compileCoreKotlin").map { it.outputs.files })
    libraries.from(sourceSets["core"].output)
}
