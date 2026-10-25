plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-remap"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("remapper-access") {
            id = "xyz.wagyourtail.unimined.remapper.access"
            implementationClass = "xyz.wagyourtail.unimined.remapper.access.GradlePlugin"
        }
    }
}
