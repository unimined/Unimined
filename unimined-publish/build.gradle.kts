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
        create("publish") {
            id = "xyz.wagyourtail.unimined.publish"
            implementationClass = "xyz.wagyourtail.unimined.publish.GradlePlugin"
        }
    }
}
