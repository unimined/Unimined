plugins {
    `java-gradle-plugin`
}

dependencies {
    "coreApi"(project(":unimined-api"))
    api(project(":unimined-transform"))
    // Linemap.applyJar — linemap-extra writer shared with UML
    "coreImplementation"(libs.unimined.mapping.remapper)

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
