plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-minecraft"))
    api(project(":unimined-remap"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("transform") {
            id = "xyz.wagyourtail.unimined.transform"
            implementationClass = "xyz.wagyourtail.unimined.transform.GradlePlugin"
        }
    }
}
