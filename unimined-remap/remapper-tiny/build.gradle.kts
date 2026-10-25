plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-remap"))
    implementation(libs.tiny.remapper)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("remapper-tiny") {
            id = "xyz.wagyourtail.unimined.remapper.tiny"
            implementationClass = "xyz.wagyourtail.unimined.remapper.tiny.GradlePlugin"
        }
    }
}
