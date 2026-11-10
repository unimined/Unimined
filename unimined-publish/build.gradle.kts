plugins {
    `java-gradle-plugin`
}

dependencies {
    "coreApi"(project(":unimined-api"))
    api(project(":unimined-mod"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // full plugin stack for the publish functional test — the TestKit
    // build applies the frontend + this plugin by id; acyclic (the
    // frontend does not depend on unimined-publish)
    testGradleImplementation(project(":unimined-frontend"))
}

gradlePlugin {
    plugins {
        create("publish") {
            id = "xyz.wagyourtail.unimined.publish"
            implementationClass = "xyz.wagyourtail.unimined.publish.GradlePlugin"
        }
    }
}
