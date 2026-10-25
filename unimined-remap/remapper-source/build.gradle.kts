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
        create("remapper-source") {
            id = "xyz.wagyourtail.unimined.remapper.source"
            implementationClass = "xyz.wagyourtail.unimined.remapper.source.GradlePlugin"
        }
    }
}
