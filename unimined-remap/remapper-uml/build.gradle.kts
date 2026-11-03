plugins {
    `java-gradle-plugin`
}

dependencies {
    "coreApi"(project(":unimined-remap"))
    "coreApi"(project(":unimined-api"))
    "coreApi"(libs.unimined.mapping.library)
    "coreApi"(libs.unimined.mapping.remapper)
    api(project(":unimined-remap"))
    api(libs.unimined.mapping.library)
    api(libs.unimined.mapping.remapper)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("remapper-uml") {
            id = "xyz.wagyourtail.unimined.remapper.uml"
            implementationClass = "xyz.wagyourtail.unimined.remapper.uml.GradlePlugin"
        }
    }
}
