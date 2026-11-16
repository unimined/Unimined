plugins {
    `java-gradle-plugin`
}

dependencies {
    "testGradleImplementation"(testFixtures(project(":unimined-api")))
    "coreApi"(project(":unimined-api"))
    "coreApi"(project(":unimined-remap"))
    api(project(":unimined-remap"))
    // compat layer: wires into the minecraft env + run-task seams,
    // unimined core carries no jvmdg surface
    implementation(project(":unimined-minecraft"))
    implementation(project(":unimined-transform"))
    implementation(project(":unimined-runs"))
    implementation(libs.jvmdowngrader)
    // their Gradle frontend — used only for the DowngradeFlags/
    // ShadeFlags property interfaces + toFlags()/convention()
    // converters; the plugin itself is never applied
    implementation(libs.jvmdowngrader.gradle.plugin)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    "testGradleImplementation"(gradleTestKit())
    "testGradleImplementation"(libs.junit.jupiter)
    "testGradleRuntimeOnly"(libs.junit.platform.launcher)
    "testGradleImplementation"(libs.jvmdowngrader)
    "testGradleImplementation"(libs.jvmdowngrader.java.api)
}

gradlePlugin {
    plugins {
        create("jvmdg") {
            id = "xyz.wagyourtail.unimined.jvmdg"
            implementationClass = "xyz.wagyourtail.unimined.jvmdg.GradlePlugin"
        }
    }
}
