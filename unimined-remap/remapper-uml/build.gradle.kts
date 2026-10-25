plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-remap"))
    api(libs.unimined.mapping.library)

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
