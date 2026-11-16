plugins {
    `java-gradle-plugin`
}

dependencies {
    "testGradleImplementation"(testFixtures(project(":unimined-api")))
    "coreApi"(project(":unimined-api"))
    "coreApi"(project(":unimined-minecraft"))
    api(project(":unimined-minecraft"))
    // Forgix is compileOnly: mergeLoaderJars invokes the engine from
    // the `loaderMergeForgix` configuration in an isolated classloader
    // (loaderMerge.forgixVersion picks the version), and the
    // withPlugin detection types only link when the upstream plugin is
    // actually applied
    compileOnly(libs.forgix)

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
