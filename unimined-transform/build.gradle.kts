plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-minecraft"))
    "coreApi"(project(":unimined-remap"))
    "coreApi"(project(":unimined-api"))
    "coreApi"(libs.asm)
    api(project(":unimined-remap"))
    "coreApi"(libs.classtransform)
    "coreApi"(libs.classtransform.acp)
    api(libs.classtransform)
    implementation(libs.classtransform.acp)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("transform") {
            id = "xyz.wagyourtail.unimined.transform"
            implementationClass = "xyz.wagyourtail.unimined.transform.GradlePlugin"
        }
    }
}
