plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-transform"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(project(":unimined-source:decompiler-vineflower"))
}

gradlePlugin {
    plugins {
        create("source") {
            id = "xyz.wagyourtail.unimined.source"
            implementationClass = "xyz.wagyourtail.unimined.source.GradlePlugin"
        }
    }
}
