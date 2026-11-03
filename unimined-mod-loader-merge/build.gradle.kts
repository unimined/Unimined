plugins {
    `java-gradle-plugin`
}

dependencies {
    "coreApi"(project(":unimined-api"))
    api(project(":unimined-mod"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("modLoaderMerge") {
            id = "xyz.wagyourtail.unimined.mod-loader-merge"
            implementationClass = "xyz.wagyourtail.unimined.loadermerge.GradlePlugin"
        }
    }
}
