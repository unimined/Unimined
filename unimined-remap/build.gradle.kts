plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-api"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // functional test exercises the real engine through remappedView()
    testImplementation(project(":unimined-remap:remapper-tiny"))
}

gradlePlugin {
    plugins {
        create("remap") {
            id = "xyz.wagyourtail.unimined.remap"
            implementationClass = "xyz.wagyourtail.unimined.remap.GradlePlugin"
        }
    }
}
