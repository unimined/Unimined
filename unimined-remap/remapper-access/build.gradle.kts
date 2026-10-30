plugins {
    `java-gradle-plugin`
}

dependencies {
    "coreApi"(project(":unimined-remap"))
    "coreApi"(project(":unimined-api"))
    "coreApi"(libs.unimined.mapping.library)
    api(project(":unimined-remap"))
    // AW/CT/AT directive parsing (AccessElement-bearing trees)
    api(libs.unimined.mapping.library)
    implementation(libs.asm)

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
