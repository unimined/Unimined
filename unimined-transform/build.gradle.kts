plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-minecraft"))
    "coreApi"(project(":unimined-remap"))
    "coreApi"(project(":unimined-api"))
    "coreApi"(libs.asm)
    api(project(":unimined-remap"))
    // dev-artifact access apply for loader-less (common) envs;
    // family wirings carry their own dependency on this module
    implementation(project(":unimined-remap:remapper-access"))
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
