plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-api"))
    // the default composer merges typed views through UML's tree/sink model
    api(libs.unimined.mapping.library)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("mapping") {
            id = "xyz.wagyourtail.unimined.mapping"
            implementationClass = "xyz.wagyourtail.unimined.mapping.GradlePlugin"
        }
    }
}
