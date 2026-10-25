plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-transform"))
    api(project(":mc-launcher"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("runs") {
            id = "xyz.wagyourtail.unimined.runs"
            implementationClass = "xyz.wagyourtail.unimined.runs.GradlePlugin"
        }
    }
}
