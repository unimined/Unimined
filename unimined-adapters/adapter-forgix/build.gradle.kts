plugins {
    `java-gradle-plugin`
}

dependencies {
    "coreApi"(project(":unimined-api"))
    "coreApi"(project(":unimined-minecraft"))
    api(project(":unimined-minecraft"))
    // Forgix's engine is invoked directly by mergeLoaderJars — the
    // upstream plugin layer (forgix ext/mergeJars) can't merge on
    // flat projects, so this is a real dep, not compileOnly
    implementation(libs.forgix)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    "testGradleImplementation"(gradleTestKit())
    "testGradleImplementation"(libs.junit.jupiter)
    "testGradleImplementation"(libs.forgix)
    "testGradleRuntimeOnly"(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("adapterForgix") {
            id = "xyz.wagyourtail.unimined.adapter-forgix"
            implementationClass = "xyz.wagyourtail.unimined.loadermerge.GradlePlugin"
        }
    }
}
