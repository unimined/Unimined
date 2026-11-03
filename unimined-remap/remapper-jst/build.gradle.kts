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
        create("remapper-jst") {
            id = "xyz.wagyourtail.unimined.remapper.jst"
            implementationClass = "xyz.wagyourtail.unimined.remapper.jst.GradlePlugin"
        }
    }
}
