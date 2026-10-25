plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-mapping"))
    api(libs.unimined.mapping.library)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("mapping-uml") {
            id = "xyz.wagyourtail.unimined.mapping.uml"
            implementationClass = "xyz.wagyourtail.unimined.mapping.uml.GradlePlugin"
        }
    }
}
